package com.songly.social;

import com.songly.profile.ProfileDirectory;
import com.songly.social.dto.BlockedUserResponse;
import com.songly.social.dto.FriendRequestDirection;
import com.songly.social.dto.FriendRequestResponse;
import com.songly.social.dto.FriendResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SocialGraphService implements SocialGraph {

	private final FriendRequestRepository friendRequestRepository;
	private final FriendshipRepository friendshipRepository;
	private final BlockRepository blockRepository;
	private final ProfileDirectory profileDirectory;
	private final ApplicationEventPublisher eventPublisher;

	SocialGraphService(FriendRequestRepository friendRequestRepository, FriendshipRepository friendshipRepository,
			BlockRepository blockRepository, ProfileDirectory profileDirectory,
			ApplicationEventPublisher eventPublisher) {
		this.friendRequestRepository = friendRequestRepository;
		this.friendshipRepository = friendshipRepository;
		this.blockRepository = blockRepository;
		this.profileDirectory = profileDirectory;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Jeśli adresat sam wcześniej wysłał zaproszenie w drugą stronę, nie tworzymy
	 * duplikatu tylko akceptujemy tamto — obie strony wyraziły chęć znajomości.
	 */
	@Transactional
	FriendRequestResponse sendRequest(UUID requesterId, UUID addresseeId) {
		if (requesterId.equals(addresseeId)) {
			throw new SelfActionNotAllowedException();
		}
		requireUserExists(addresseeId);
		requireNotBlocked(requesterId, addresseeId);
		if (friendshipRepository.existsById(FriendshipId.of(requesterId, addresseeId))) {
			throw new AlreadyFriendsException();
		}
		if (friendRequestRepository
				.findByRequesterIdAndAddresseeIdAndStatus(requesterId, addresseeId, FriendRequestStatus.PENDING)
				.isPresent()) {
			throw new FriendRequestAlreadyPendingException();
		}
		Instant now = Instant.now();
		Optional<FriendRequest> reverse = friendRequestRepository.findByRequesterIdAndAddresseeIdAndStatus(addresseeId,
				requesterId, FriendRequestStatus.PENDING);
		if (reverse.isPresent()) {
			FriendRequest request = reverse.get();
			request.accept(now);
			friendshipRepository.save(Friendship.between(requesterId, addresseeId, now));
			eventPublisher.publishEvent(new FriendRequestAcceptedEvent(request.getId(), request.getRequesterId(),
					request.getAddresseeId(), now));
			return toResponse(request);
		}
		FriendRequest request = new FriendRequest(UUID.randomUUID(), requesterId, addresseeId, now);
		friendRequestRepository.save(request);
		eventPublisher.publishEvent(new FriendRequestSentEvent(request.getId(), requesterId, addresseeId, now));
		return toResponse(request);
	}

	@Transactional(readOnly = true)
	Page<FriendRequestResponse> listRequests(UUID userId, FriendRequestDirection direction, Pageable pageable) {
		Page<FriendRequest> page = direction == FriendRequestDirection.INCOMING
				? friendRequestRepository.findByAddresseeIdAndStatusOrderByCreatedAtDesc(userId,
						FriendRequestStatus.PENDING, pageable)
				: friendRequestRepository.findByRequesterIdAndStatusOrderByCreatedAtDesc(userId,
						FriendRequestStatus.PENDING, pageable);
		Set<UUID> userIds = page.getContent().stream()
				.flatMap(request -> Stream.of(request.getRequesterId(), request.getAddresseeId()))
				.collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory.displayNames(userIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(userIds);
		return page.map(request -> toResponse(request, names, avatars));
	}

	@Transactional
	FriendRequestResponse acceptRequest(UUID requestId, UUID currentUserId) {
		FriendRequest request = pendingRequestFor(requestId, FriendRequest::getAddresseeId, currentUserId);
		Instant now = Instant.now();
		request.accept(now);
		friendshipRepository.save(Friendship.between(request.getRequesterId(), request.getAddresseeId(), now));
		eventPublisher.publishEvent(new FriendRequestAcceptedEvent(request.getId(), request.getRequesterId(),
				request.getAddresseeId(), now));
		return toResponse(request);
	}

	@Transactional
	FriendRequestResponse declineRequest(UUID requestId, UUID currentUserId) {
		FriendRequest request = pendingRequestFor(requestId, FriendRequest::getAddresseeId, currentUserId);
		request.decline(Instant.now());
		return toResponse(request);
	}

	@Transactional
	void cancelRequest(UUID requestId, UUID currentUserId) {
		FriendRequest request = pendingRequestFor(requestId, FriendRequest::getRequesterId, currentUserId);
		friendRequestRepository.delete(request);
	}

	@Transactional(readOnly = true)
	Page<FriendResponse> listFriends(UUID userId, Pageable pageable) {
		Page<Friendship> page = friendshipRepository.findByUserAIdOrUserBIdOrderByCreatedAtDesc(userId, userId,
				pageable);
		Set<UUID> friendIds = page.getContent().stream().map(friendship -> friendship.otherThan(userId))
				.collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory.displayNames(friendIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(friendIds);
		return page.map(
				friendship -> new FriendResponse(friendship.otherThan(userId), names.get(friendship.otherThan(userId)),
						avatars.get(friendship.otherThan(userId)), friendship.getCreatedAt()));
	}

	@Transactional
	void removeFriend(UUID userId, UUID friendId) {
		FriendshipId id = FriendshipId.of(userId, friendId);
		if (!friendshipRepository.existsById(id)) {
			throw new FriendshipNotFoundException();
		}
		friendshipRepository.deleteById(id);
	}

	/**
	 * Idempotentne. Blokada zrywa znajomość i usuwa oczekujące zaproszenia w obu
	 * kierunkach — po odblokowaniu relację trzeba zbudować od nowa.
	 */
	@Transactional
	void blockUser(UUID blockerId, UUID blockedId) {
		if (blockerId.equals(blockedId)) {
			throw new SelfActionNotAllowedException();
		}
		requireUserExists(blockedId);
		if (!blockRepository.existsById(new BlockId(blockerId, blockedId))) {
			blockRepository.save(new Block(blockerId, blockedId, Instant.now()));
		}
		friendshipRepository.deleteById(FriendshipId.of(blockerId, blockedId));
		friendRequestRepository.deleteByRequesterIdAndAddresseeIdAndStatus(blockerId, blockedId,
				FriendRequestStatus.PENDING);
		friendRequestRepository.deleteByRequesterIdAndAddresseeIdAndStatus(blockedId, blockerId,
				FriendRequestStatus.PENDING);
	}

	@Transactional
	void unblockUser(UUID blockerId, UUID blockedId) {
		blockRepository.deleteById(new BlockId(blockerId, blockedId));
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		friendRequestRepository.deleteByRequesterIdOrAddresseeId(userId, userId);
		friendshipRepository.deleteByUserAIdOrUserBId(userId, userId);
		blockRepository.deleteByBlockerIdOrBlockedId(userId, userId);
	}

	@Transactional(readOnly = true)
	Page<BlockedUserResponse> listBlockedUsers(UUID blockerId, Pageable pageable) {
		Page<Block> page = blockRepository.findByBlockerIdOrderByCreatedAtDesc(blockerId, pageable);
		Map<UUID, String> names = profileDirectory
				.displayNames(page.getContent().stream().map(Block::getBlockedId).collect(Collectors.toSet()));
		return page.map(block -> new BlockedUserResponse(block.getBlockedId(), names.get(block.getBlockedId()),
				block.getCreatedAt()));
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> friendIds(UUID userId) {
		return friendshipRepository.findByUserAIdOrUserBId(userId, userId).stream()
				.map(friendship -> friendship.otherThan(userId)).collect(Collectors.toSet());
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isBlockedEitherWay(UUID userId, UUID otherUserId) {
		return blockRepository.existsById(new BlockId(userId, otherUserId))
				|| blockRepository.existsById(new BlockId(otherUserId, userId));
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> blockedEitherWayIds(UUID userId) {
		return Stream
				.concat(blockRepository.findByBlockerId(userId).stream().map(Block::getBlockedId),
						blockRepository.findByBlockedId(userId).stream().map(Block::getBlockerId))
				.collect(Collectors.toSet());
	}

	/**
	 * Zwraca oczekujące zaproszenie tylko jeśli {@code currentUserId} jest jego
	 * właściwą stroną — cudze zaproszenia zgłaszamy jako nieistniejące, żeby nie
	 * ujawniać relacji między innymi użytkownikami.
	 */
	private FriendRequest pendingRequestFor(UUID requestId, Function<FriendRequest, UUID> side, UUID currentUserId) {
		FriendRequest request = friendRequestRepository.findById(requestId)
				.filter(r -> side.apply(r).equals(currentUserId)).orElseThrow(FriendRequestNotFoundException::new);
		if (request.getStatus() != FriendRequestStatus.PENDING) {
			throw new FriendRequestAlreadyHandledException();
		}
		return request;
	}

	private void requireUserExists(UUID userId) {
		if (profileDirectory.displayNames(Set.of(userId)).isEmpty()) {
			throw new UserNotFoundException();
		}
	}

	private void requireNotBlocked(UUID userId, UUID otherUserId) {
		if (isBlockedEitherWay(userId, otherUserId)) {
			throw new UserBlockedException();
		}
	}

	private FriendRequestResponse toResponse(FriendRequest request) {
		List<UUID> ids = List.of(request.getRequesterId(), request.getAddresseeId());
		return toResponse(request, profileDirectory.displayNames(ids), profileDirectory.avatarUrls(ids));
	}

	private FriendRequestResponse toResponse(FriendRequest request, Map<UUID, String> names,
			Map<UUID, String> avatars) {
		return new FriendRequestResponse(request.getId(), request.getRequesterId(), names.get(request.getRequesterId()),
				avatars.get(request.getRequesterId()), request.getAddresseeId(), names.get(request.getAddresseeId()),
				avatars.get(request.getAddresseeId()), request.getStatus(), request.getCreatedAt());
	}
}
