package com.songly.community;

import com.songly.community.dto.CommunityMemberResponse;
import com.songly.community.dto.CommunityResponse;
import com.songly.profile.ProfileDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CommunityService {

	private final CommunityRepository communityRepository;
	private final CommunityMemberRepository memberRepository;
	private final ProfileDirectory profileDirectory;

	CommunityService(CommunityRepository communityRepository, CommunityMemberRepository memberRepository,
			ProfileDirectory profileDirectory) {
		this.communityRepository = communityRepository;
		this.memberRepository = memberRepository;
		this.profileDirectory = profileDirectory;
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
		return new CommunityResponse(community.getId(), community.getName(), community.getDescription(), ownerId, 1,
				CommunityRole.OWNER, now);
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
		return memberships.map(membership -> {
			Community community = communities.get(membership.getCommunityId());
			return toResponse(community, counts.getOrDefault(community.getId(), 0L), membership.getRole());
		});
	}

	/** Idempotentne — ponowne dołączenie nie zmienia istniejącego członkostwa. */
	@Transactional
	void join(UUID communityId, UUID userId) {
		requireCommunity(communityId);
		CommunityMemberId id = new CommunityMemberId(communityId, userId);
		if (!memberRepository.existsById(id)) {
			memberRepository.save(new CommunityMember(communityId, userId, CommunityRole.MEMBER, Instant.now()));
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

	@Transactional(readOnly = true)
	Page<CommunityMemberResponse> listMembers(UUID communityId, Pageable pageable) {
		requireCommunity(communityId);
		Page<CommunityMember> page = memberRepository.findByCommunityIdOrderByJoinedAtAsc(communityId, pageable);
		Map<UUID, String> names = profileDirectory
				.displayNames(page.getContent().stream().map(CommunityMember::getUserId).collect(Collectors.toSet()));
		return page.map(member -> new CommunityMemberResponse(member.getUserId(), names.get(member.getUserId()),
				member.getRole(), member.getJoinedAt()));
	}

	private Page<CommunityResponse> enrich(Page<Community> page, UUID viewerId) {
		List<UUID> communityIds = page.getContent().stream().map(Community::getId).toList();
		Map<UUID, Long> counts = memberCounts(communityIds);
		Map<UUID, CommunityRole> myRoles = communityIds.isEmpty() ? Map.of()
				: memberRepository.findByUserIdAndCommunityIdIn(viewerId, communityIds).stream()
						.collect(Collectors.toMap(CommunityMember::getCommunityId, CommunityMember::getRole));
		return page.map(community -> toResponse(community, counts.getOrDefault(community.getId(), 0L),
				myRoles.get(community.getId())));
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

	private CommunityResponse toResponse(Community community, long memberCount, CommunityRole myRole) {
		return new CommunityResponse(community.getId(), community.getName(), community.getDescription(),
				community.getOwnerId(), memberCount, myRole, community.getCreatedAt());
	}
}
