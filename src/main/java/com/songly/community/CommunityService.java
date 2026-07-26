package com.songly.community;

import com.songly.community.dto.CommunityMemberResponse;
import com.songly.community.dto.CommunityResponse;
import com.songly.profile.ProfileDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CommunityService implements CommunityMembership {

	private final CommunityRepository communityRepository;
	private final CommunityMemberRepository memberRepository;
	private final ProfileDirectory profileDirectory;
	private final ApplicationEventPublisher eventPublisher;

	CommunityService(CommunityRepository communityRepository, CommunityMemberRepository memberRepository,
			ProfileDirectory profileDirectory, ApplicationEventPublisher eventPublisher) {
		this.communityRepository = communityRepository;
		this.memberRepository = memberRepository;
		this.profileDirectory = profileDirectory;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	CommunityResponse create(UUID ownerId, String name, String description) {
		if (communityRepository.existsByNameIgnoreCase(name)) {
			throw new CommunityNameTakenException();
		}
		Instant now = Instant.now();
		Community community = new Community(UUID.randomUUID(), name, description, ownerId, now);
		communityRepository.save(community);
		memberRepository.save(new CommunityMember(community.getId(), ownerId, CommunityRole.OWNER, now));
		return toResponse(community, 1, CommunityRole.OWNER);
	}

	@Transactional
	CommunityResponse update(UUID communityId, UUID requesterId, String name, String description) {
		Community community = requireCommunity(communityId);
		requireOwner(community, requesterId);
		if (!community.getName().equalsIgnoreCase(name) && communityRepository.existsByNameIgnoreCase(name)) {
			throw new CommunityNameTakenException();
		}
		community.update(name, description);
		return toResponse(community, memberRepository.countByCommunityId(communityId),
				roleOf(communityId, requesterId));
	}

	@Transactional
	void delete(UUID communityId, UUID requesterId) {
		Community community = requireCommunity(communityId);
		requireOwner(community, requesterId);
		deleteWithDependents(community);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean exists(UUID communityId) {
		return communityRepository.existsById(communityId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isMember(UUID communityId, UUID userId) {
		return memberRepository.existsById(new CommunityMemberId(communityId, userId));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isOwner(UUID communityId, UUID userId) {
		return communityRepository.findById(communityId).map(community -> community.getOwnerId().equals(userId))
				.orElse(false);
	}

	/**
	 * Zdarzenie leci <b>przed</b> usunięciem wiersza: {@code posts} ma klucz obcy
	 * do {@code communities}, więc moduł content musi zdążyć posprzątać swoje
	 * wiersze w tej samej transakcji (Claude.md §9, ten sam mechanizm co przy
	 * kasowaniu konta).
	 */
	private void deleteWithDependents(Community community) {
		eventPublisher.publishEvent(new CommunityDeletedEvent(community.getId()));
		communityRepository.delete(community);
	}

	@Transactional(readOnly = true)
	CommunityResponse get(UUID communityId, UUID viewerId) {
		Community community = requireCommunity(communityId);
		return toResponse(community, memberRepository.countByCommunityId(communityId), roleOf(communityId, viewerId));
	}

	@Transactional(readOnly = true)
	Page<CommunityResponse> list(UUID viewerId, Pageable pageable) {
		Page<Community> page = communityRepository.findAllByOrderByNameAsc(pageable);
		return enrich(page, viewerId);
	}

	@Transactional(readOnly = true)
	Page<CommunityResponse> listMine(UUID userId, Pageable pageable) {
		Page<CommunityMember> memberships = memberRepository.findByUserIdOrderByJoinedAtDesc(userId, pageable);
		List<UUID> communityIds = memberships.getContent().stream().map(CommunityMember::getCommunityId).toList();
		Map<UUID, Community> communities = communityRepository.findAllById(communityIds).stream()
				.collect(Collectors.toMap(Community::getId, Function.identity()));
		Map<UUID, Long> counts = memberCounts(communityIds);
		Set<UUID> ownerIds = communities.values().stream().map(Community::getOwnerId).collect(Collectors.toSet());
		Map<UUID, String> ownerNames = profileDirectory.displayNames(ownerIds);
		Map<UUID, String> ownerAvatars = profileDirectory.avatarUrls(ownerIds);
		return memberships.map(membership -> {
			Community community = communities.get(membership.getCommunityId());
			return toResponse(community, counts.getOrDefault(community.getId(), 0L), membership.getRole(), ownerNames,
					ownerAvatars);
		});
	}

	/** Idempotentne — ponowne dołączenie nie zmienia istniejącego członkostwa. */
	@Transactional
	void join(UUID communityId, UUID userId) {
		Community community = requireCommunity(communityId);
		CommunityMemberId id = new CommunityMemberId(communityId, userId);
		if (!memberRepository.existsById(id)) {
			Instant now = Instant.now();
			memberRepository.save(new CommunityMember(communityId, userId, CommunityRole.MEMBER, now));
			eventPublisher
					.publishEvent(new CommunityMemberJoinedEvent(communityId, community.getOwnerId(), userId, now));
		}
	}

	@Transactional
	void leave(UUID communityId, UUID userId) {
		requireCommunity(communityId);
		memberRepository.findById(new CommunityMemberId(communityId, userId)).ifPresent(membership -> {
			if (membership.getRole() == CommunityRole.OWNER) {
				throw new OwnerCannotLeaveException();
			}
			memberRepository.delete(membership);
		});
	}

	/**
	 * Najpierw członkostwa usera (w tym jego własne OWNER w społecznościach, które
	 * za chwilę i tak znikną), potem społeczności które posiada — usunięcie
	 * community kaskadowo (ON DELETE CASCADE community_id) zabiera członkostwa
	 * pozostałych userów w tej społeczności.
	 *
	 * <p>
	 * Celowo <b>nie</b> masowym {@code deleteByOwnerId}: kasowanie wsadowe omija
	 * cykl życia encji, więc nie poszłoby ani jedno {@link CommunityDeletedEvent} i
	 * posty w tych społecznościach zostałyby z wiszącym kluczem obcym.
	 */
	@Transactional
	void deleteAccountData(UUID userId) {
		memberRepository.deleteByUserId(userId);
		communityRepository.findByOwnerId(userId).forEach(this::deleteWithDependents);
	}

	@Transactional(readOnly = true)
	Page<CommunityMemberResponse> listMembers(UUID communityId, Pageable pageable) {
		requireCommunity(communityId);
		Page<CommunityMember> page = memberRepository.findByCommunityIdOrderByJoinedAtAsc(communityId, pageable);
		Set<UUID> memberIds = page.getContent().stream().map(CommunityMember::getUserId).collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory.displayNames(memberIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(memberIds);
		return page.map(member -> new CommunityMemberResponse(member.getUserId(), names.get(member.getUserId()),
				avatars.get(member.getUserId()), member.getRole(), member.getJoinedAt()));
	}

	private Page<CommunityResponse> enrich(Page<Community> page, UUID viewerId) {
		List<UUID> communityIds = page.getContent().stream().map(Community::getId).toList();
		Map<UUID, Long> counts = memberCounts(communityIds);
		Map<UUID, CommunityRole> myRoles = communityIds.isEmpty()
				? Map.of()
				: memberRepository.findByUserIdAndCommunityIdIn(viewerId, communityIds).stream()
						.collect(Collectors.toMap(CommunityMember::getCommunityId, CommunityMember::getRole));
		Set<UUID> ownerIds = page.getContent().stream().map(Community::getOwnerId).collect(Collectors.toSet());
		Map<UUID, String> ownerNames = profileDirectory.displayNames(ownerIds);
		Map<UUID, String> ownerAvatars = profileDirectory.avatarUrls(ownerIds);
		return page.map(community -> toResponse(community, counts.getOrDefault(community.getId(), 0L),
				myRoles.get(community.getId()), ownerNames, ownerAvatars));
	}

	private Map<UUID, Long> memberCounts(List<UUID> communityIds) {
		if (communityIds.isEmpty()) {
			return Map.of();
		}
		return memberRepository.countByCommunityIdIn(communityIds).stream()
				.collect(Collectors.toMap(CommunityMemberRepository.CommunityIdCount::getCommunityId,
						CommunityMemberRepository.CommunityIdCount::getMemberCount));
	}

	private CommunityRole roleOf(UUID communityId, UUID userId) {
		return memberRepository.findById(new CommunityMemberId(communityId, userId)).map(CommunityMember::getRole)
				.orElse(null);
	}

	private Community requireCommunity(UUID communityId) {
		return communityRepository.findById(communityId).orElseThrow(CommunityNotFoundException::new);
	}

	private void requireOwner(Community community, UUID requesterId) {
		if (!community.getOwnerId().equals(requesterId)) {
			throw new NotCommunityOwnerException();
		}
	}

	/**
	 * Pojedyncza społeczność — dane założyciela dobieramy jednym zapytaniem po jego
	 * id.
	 */
	private CommunityResponse toResponse(Community community, long memberCount, CommunityRole myRole) {
		Set<UUID> ownerId = Set.of(community.getOwnerId());
		return toResponse(community, memberCount, myRole, profileDirectory.displayNames(ownerId),
				profileDirectory.avatarUrls(ownerId));
	}

	/**
	 * Wariant wsadowy: nazwy/avatary założycieli podane z zewnątrz (jedno zapytanie
	 * na całą stronę), żeby listy nie robiły N+1 do modułu profile.
	 */
	private CommunityResponse toResponse(Community community, long memberCount, CommunityRole myRole,
			Map<UUID, String> ownerNames, Map<UUID, String> ownerAvatars) {
		UUID ownerId = community.getOwnerId();
		return new CommunityResponse(community.getId(), community.getName(), community.getDescription(), ownerId,
				ownerNames.get(ownerId), ownerAvatars.get(ownerId), memberCount, myRole, community.getCreatedAt());
	}
}
