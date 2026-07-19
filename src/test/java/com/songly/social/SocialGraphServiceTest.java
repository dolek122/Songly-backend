package com.songly.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.profile.ProfileDirectory;
import com.songly.social.dto.FriendRequestResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SocialGraphServiceTest {

	@Mock
	private FriendRequestRepository friendRequestRepository;

	@Mock
	private FriendshipRepository friendshipRepository;

	@Mock
	private BlockRepository blockRepository;

	@Mock
	private ProfileDirectory profileDirectory;

	private SocialGraphService socialGraphService;

	private final UUID requesterId = UUID.randomUUID();
	private final UUID addresseeId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		socialGraphService = new SocialGraphService(friendRequestRepository, friendshipRepository, blockRepository,
				profileDirectory);
		lenient().when(profileDirectory.displayNames(anyCollection()))
				.thenReturn(Map.of(requesterId, "Ada", addresseeId, "Bono"));
	}

	@Test
	void sendRequest_throws_whenAddresseeIsSelf() {
		assertThatThrownBy(() -> socialGraphService.sendRequest(requesterId, requesterId))
				.isInstanceOf(SelfActionNotAllowedException.class);
	}

	@Test
	void sendRequest_throws_whenAddresseeDoesNotExist() {
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of());

		assertThatThrownBy(() -> socialGraphService.sendRequest(requesterId, addresseeId))
				.isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void sendRequest_throws_whenBlockedEitherWay() {
		when(blockRepository.existsById(new BlockId(requesterId, addresseeId))).thenReturn(false);
		when(blockRepository.existsById(new BlockId(addresseeId, requesterId))).thenReturn(true);

		assertThatThrownBy(() -> socialGraphService.sendRequest(requesterId, addresseeId))
				.isInstanceOf(UserBlockedException.class);
	}

	@Test
	void sendRequest_throws_whenAlreadyFriends() {
		when(friendshipRepository.existsById(FriendshipId.of(requesterId, addresseeId))).thenReturn(true);

		assertThatThrownBy(() -> socialGraphService.sendRequest(requesterId, addresseeId))
				.isInstanceOf(AlreadyFriendsException.class);
	}

	@Test
	void sendRequest_throws_whenPendingRequestAlreadyExists() {
		when(friendRequestRepository.findByRequesterIdAndAddresseeIdAndStatus(requesterId, addresseeId,
				FriendRequestStatus.PENDING))
				.thenReturn(Optional.of(new FriendRequest(UUID.randomUUID(), requesterId, addresseeId, Instant.now())));

		assertThatThrownBy(() -> socialGraphService.sendRequest(requesterId, addresseeId))
				.isInstanceOf(FriendRequestAlreadyPendingException.class);
	}

	@Test
	void sendRequest_acceptsReversePendingRequest_insteadOfCreatingDuplicate() {
		FriendRequest reverse = new FriendRequest(UUID.randomUUID(), addresseeId, requesterId, Instant.now());
		when(friendRequestRepository.findByRequesterIdAndAddresseeIdAndStatus(requesterId, addresseeId,
				FriendRequestStatus.PENDING)).thenReturn(Optional.empty());
		when(friendRequestRepository.findByRequesterIdAndAddresseeIdAndStatus(addresseeId, requesterId,
				FriendRequestStatus.PENDING)).thenReturn(Optional.of(reverse));

		FriendRequestResponse response = socialGraphService.sendRequest(requesterId, addresseeId);

		assertThat(response.status()).isEqualTo(FriendRequestStatus.ACCEPTED);
		verify(friendshipRepository).save(any(Friendship.class));
		verify(friendRequestRepository, never()).save(any(FriendRequest.class));
	}

	@Test
	void sendRequest_createsPendingRequest() {
		FriendRequestResponse response = socialGraphService.sendRequest(requesterId, addresseeId);

		assertThat(response.status()).isEqualTo(FriendRequestStatus.PENDING);
		assertThat(response.requesterDisplayName()).isEqualTo("Ada");
		assertThat(response.addresseeDisplayName()).isEqualTo("Bono");
		verify(friendRequestRepository).save(any(FriendRequest.class));
	}

	@Test
	void acceptRequest_createsFriendship() {
		FriendRequest request = new FriendRequest(UUID.randomUUID(), requesterId, addresseeId, Instant.now());
		when(friendRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		FriendRequestResponse response = socialGraphService.acceptRequest(request.getId(), addresseeId);

		assertThat(response.status()).isEqualTo(FriendRequestStatus.ACCEPTED);
		verify(friendshipRepository).save(any(Friendship.class));
	}

	@Test
	void acceptRequest_reportsNotFound_whenCurrentUserIsNotAddressee() {
		FriendRequest request = new FriendRequest(UUID.randomUUID(), requesterId, addresseeId, Instant.now());
		when(friendRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> socialGraphService.acceptRequest(request.getId(), requesterId))
				.isInstanceOf(FriendRequestNotFoundException.class);
	}

	@Test
	void acceptRequest_throws_whenAlreadyHandled() {
		FriendRequest request = new FriendRequest(UUID.randomUUID(), requesterId, addresseeId, Instant.now());
		request.decline(Instant.now());
		when(friendRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> socialGraphService.acceptRequest(request.getId(), addresseeId))
				.isInstanceOf(FriendRequestAlreadyHandledException.class);
	}

	@Test
	void cancelRequest_deletes_whenCurrentUserIsRequester() {
		FriendRequest request = new FriendRequest(UUID.randomUUID(), requesterId, addresseeId, Instant.now());
		when(friendRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		socialGraphService.cancelRequest(request.getId(), requesterId);

		verify(friendRequestRepository).delete(request);
	}

	@Test
	void cancelRequest_reportsNotFound_whenCurrentUserIsNotRequester() {
		FriendRequest request = new FriendRequest(UUID.randomUUID(), requesterId, addresseeId, Instant.now());
		when(friendRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> socialGraphService.cancelRequest(request.getId(), addresseeId))
				.isInstanceOf(FriendRequestNotFoundException.class);
	}

	@Test
	void removeFriend_throws_whenNotFriends() {
		assertThatThrownBy(() -> socialGraphService.removeFriend(requesterId, addresseeId))
				.isInstanceOf(FriendshipNotFoundException.class);
	}

	@Test
	void blockUser_removesFriendshipAndPendingRequestsBothWays() {
		socialGraphService.blockUser(requesterId, addresseeId);

		verify(blockRepository).save(any(Block.class));
		verify(friendshipRepository).deleteById(FriendshipId.of(requesterId, addresseeId));
		verify(friendRequestRepository).deleteByRequesterIdAndAddresseeIdAndStatus(requesterId, addresseeId,
				FriendRequestStatus.PENDING);
		verify(friendRequestRepository).deleteByRequesterIdAndAddresseeIdAndStatus(addresseeId, requesterId,
				FriendRequestStatus.PENDING);
	}

	@Test
	void blockUser_isIdempotent() {
		when(blockRepository.existsById(new BlockId(requesterId, addresseeId))).thenReturn(true);

		socialGraphService.blockUser(requesterId, addresseeId);

		verify(blockRepository, never()).save(any(Block.class));
	}

	@Test
	void blockUser_throws_whenBlockingSelf() {
		assertThatThrownBy(() -> socialGraphService.blockUser(requesterId, requesterId))
				.isInstanceOf(SelfActionNotAllowedException.class);
	}

	@Test
	void blockedEitherWayIds_combinesBothDirections() {
		UUID blockedByMe = UUID.randomUUID();
		UUID blockingMe = UUID.randomUUID();
		when(blockRepository.findByBlockerId(requesterId))
				.thenReturn(List.of(new Block(requesterId, blockedByMe, Instant.now())));
		when(blockRepository.findByBlockedId(requesterId))
				.thenReturn(List.of(new Block(blockingMe, requesterId, Instant.now())));

		assertThat(socialGraphService.blockedEitherWayIds(requesterId)).containsExactlyInAnyOrder(blockedByMe,
				blockingMe);
	}

	@Test
	void friendIds_returnsOtherEndOfEachFriendship() {
		UUID other = UUID.randomUUID();
		when(friendshipRepository.findByUserAIdOrUserBId(requesterId, requesterId))
				.thenReturn(List.of(Friendship.between(requesterId, other, Instant.now())));

		assertThat(socialGraphService.friendIds(requesterId)).containsExactly(other);
	}
}
