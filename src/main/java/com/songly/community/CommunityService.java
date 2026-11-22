package com.songly.community;

import com.songly.community.dto.BanDetailsResponse;
import com.songly.community.dto.CommunityBanResponse;
import com.songly.community.dto.CommunityMemberResponse;
import com.songly.community.dto.CommunityMembershipStatus;
import com.songly.community.dto.CommunityResponse;
import com.songly.community.dto.EscalatedAppealResponse;
import com.songly.community.dto.MembershipStatusResponse;
import com.songly.profile.ProfileDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CommunityService implements CommunityMembership, CommunityDirectory {

	private static final String NAME_INDEX = "uq_communities_name_lower";

	private final CommunityRepository communityRepository;
	private final CommunityMemberRepository memberRepository;
	private final CommunityBanRepository banRepository;
	private final ProfileDirectory profileDirectory;
	private final ApplicationEventPublisher eventPublisher;

	CommunityService(CommunityRepository communityRepository, CommunityMemberRepository memberRepository,
			CommunityBanRepository banRepository, ProfileDirectory profileDirectory,
			ApplicationEventPublisher eventPublisher) {
		this.communityRepository = communityRepository;
		this.memberRepository = memberRepository;
		this.banRepository = banRepository;
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
		flushRespectingUniqueName();
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
		flushRespectingUniqueName();
		return toResponse(community, memberRepository.countByCommunityId(communityId),
				roleOf(communityId, requesterId));
	}

	private void flushRespectingUniqueName() {
		try {
			communityRepository.flush();
		} catch (DataIntegrityViolationException ex) {
			String cause = ex.getMostSpecificCause().getMessage();
			if (cause != null && cause.contains(NAME_INDEX)) {
				throw new CommunityNameTakenException();
			}
			throw ex;
		}
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

	@Override
	@Transactional(readOnly = true)
	public boolean canModerate(UUID communityId, UUID userId) {
		return memberRepository.findById(new CommunityMemberId(communityId, userId))
				.map(membership -> membership.getRole().canModerate()).orElse(false);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isBanned(UUID communityId, UUID userId) {
		return banRepository.existsActive(communityId, userId, Instant.now());
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> moderatorIds(UUID communityId) {
		return memberRepository.findByCommunityIdAndRoleIn(communityId, CommunityRole.MODERATING).stream()
				.map(CommunityMember::getUserId).collect(Collectors.toSet());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> nameOf(UUID communityId) {
		return communityRepository.findById(communityId).map(Community::getName);
	}

	@Transactional(readOnly = true)
	MembershipStatusResponse membershipStatus(UUID communityId, UUID userId) {
		requireCommunity(communityId);
		Optional<CommunityMember> membership = memberRepository.findById(new CommunityMemberId(communityId, userId));
		if (membership.isPresent()) {
			return new MembershipStatusResponse(CommunityMembershipStatus.MEMBER, membership.get().getRole(),
					membership.get().getJoinedAt(), null);
		}
		Instant now = Instant.now();
		return banRepository.findById(new CommunityBanId(communityId, userId)).filter(ban -> ban.isActiveAt(now))
				.map(ban -> new MembershipStatusResponse(CommunityMembershipStatus.BANNED, null, null,
						new BanDetailsResponse(ban.getReason(), ban.getCreatedAt(), ban.getExpiresAt(),
								ban.getAppealStatus(), ban.getAppealedAt())))
				.orElseGet(() -> new MembershipStatusResponse(CommunityMembershipStatus.NONE, null, null, null));
	}

	@Transactional
	void setMemberRole(UUID communityId, UUID requesterId, UUID targetUserId, CommunityRole role) {
		Community community = requireCommunity(communityId);
		requireOwner(community, requesterId);
		if (role == CommunityRole.OWNER) {
			throw new CannotAssignOwnerRoleException();
		}
		CommunityMember membership = memberRepository.findById(new CommunityMemberId(communityId, targetUserId))
				.orElseThrow(NotCommunityMemberToModerateException::new);
		if (membership.getRole() == CommunityRole.OWNER) {
			throw new CannotModerateOwnerException();
		}
		if (membership.getRole() == role) {
			return;
		}
		membership.changeRole(role);
		eventPublisher.publishEvent(new CommunityMemberRoleChangedEvent(communityId, targetUserId, requesterId,
				role.canModerate(), Instant.now()));
	}

	@Transactional
	void ban(UUID communityId, UUID requesterId, UUID targetUserId, String reason, Integer durationDays) {
		Community community = requireCommunity(communityId);
		requireModerator(communityId, requesterId);
		if (community.getOwnerId().equals(targetUserId)) {
			throw new CannotModerateOwnerException();
		}
		if (!requesterId.equals(community.getOwnerId()) && canModerate(communityId, targetUserId)) {
			throw new CannotModerateModeratorException();
		}
		memberRepository.findById(new CommunityMemberId(communityId, targetUserId)).ifPresent(memberRepository::delete);
		Instant now = Instant.now();
		Instant expiresAt = durationDays == null ? null : now.plus(Duration.ofDays(durationDays));
		CommunityBan existing = banRepository.findForUpdate(communityId, targetUserId).orElse(null);
		if (existing == null) {
			banRepository.save(new CommunityBan(communityId, targetUserId, requesterId, reason, now, expiresAt));
			eventPublisher.publishEvent(
					new CommunityMemberBannedEvent(communityId, targetUserId, requesterId, expiresAt, now));
		} else if (!existing.isActiveAt(now)) {
			existing.reimpose(requesterId, reason, now, expiresAt);
			eventPublisher.publishEvent(
					new CommunityMemberBannedEvent(communityId, targetUserId, requesterId, expiresAt, now));
		}
	}

	@Transactional
	void unban(UUID communityId, UUID requesterId, UUID targetUserId) {
		requireCommunity(communityId);
		requireModerator(communityId, requesterId);
		banRepository.findForUpdate(communityId, targetUserId).ifPresent(ban -> {
			Instant now = Instant.now();
			boolean wasActive = ban.isActiveAt(now);
			if (wasActive && ban.hasPendingAppeal()) {
				eventPublisher
						.publishEvent(new CommunityBanAppealAcceptedEvent(communityId, targetUserId, requesterId, now));
			}
			if (wasActive) {
				eventPublisher.publishEvent(new CommunityBanLiftedEvent(communityId, targetUserId, requesterId, now));
			}
			banRepository.delete(ban);
		});
	}

	@Transactional
	void appealBan(UUID communityId, UUID userId, String text) {
		requireCommunity(communityId);
		Instant now = Instant.now();
		CommunityBan ban = banRepository.findForUpdate(communityId, userId).filter(existing -> existing.isActiveAt(now))
				.orElseThrow(NoBanToAppealException::new);
		if (ban.hasAppeal()) {
			throw new BanAppealAlreadySubmittedException();
		}
		ban.submitAppeal(text, now);
		eventPublisher.publishEvent(new CommunityBanAppealSubmittedEvent(communityId, userId, now));
	}

	@Transactional
	void escalateBanAppeal(UUID communityId, UUID userId) {
		requireCommunity(communityId);
		Instant now = Instant.now();
		CommunityBan ban = banRepository.findForUpdate(communityId, userId).filter(existing -> existing.isActiveAt(now))
				.orElseThrow(NoBanToAppealException::new);
		if (!ban.hasRejectedAppeal()) {
			throw new BanAppealNotEscalatableException();
		}
		ban.escalateAppeal(now);
		eventPublisher.publishEvent(new CommunityBanAppealEscalatedEvent(communityId, userId, now));
	}

	@Transactional(readOnly = true)
	List<EscalatedAppealResponse> escalatedAppeals() {
		Instant now = Instant.now();
		List<CommunityBan> escalated = banRepository
				.findByAppealStatusOrderByAppealEscalatedAtAsc(BanAppealStatus.ESCALATED).stream()
				.filter(ban -> ban.isActiveAt(now)).toList();
		Map<UUID, String> names = communityRepository
				.findAllById(escalated.stream().map(CommunityBan::getCommunityId).collect(Collectors.toSet())).stream()
				.collect(Collectors.toMap(Community::getId, Community::getName));
		return escalated.stream()
				.map(ban -> new EscalatedAppealResponse(ban.getCommunityId(), names.get(ban.getCommunityId()),
						ban.getUserId(), ban.getReason(), ban.getCreatedAt(), ban.getExpiresAt(), ban.getAppealText(),
						ban.getAppealedAt(), ban.getAppealEscalatedAt()))
				.toList();
	}

	@Transactional
	void overturnBan(UUID communityId, UUID targetUserId, UUID adminId) {
		requireCommunity(communityId);
		Instant now = Instant.now();
		CommunityBan ban = banRepository.findForUpdate(communityId, targetUserId)
				.filter(existing -> existing.isActiveAt(now)).filter(CommunityBan::hasEscalatedAppeal)
				.orElseThrow(EscalatedAppealNotFoundException::new);
		banRepository.delete(ban);
		eventPublisher
				.publishEvent(new CommunityBanAppealEscalationAcceptedEvent(communityId, targetUserId, adminId, now));
		eventPublisher.publishEvent(new CommunityBanLiftedEvent(communityId, targetUserId, adminId, now));
	}

	@Transactional
	void rejectBanAppealEscalation(UUID communityId, UUID targetUserId, UUID adminId) {
		requireCommunity(communityId);
		Instant now = Instant.now();
		CommunityBan ban = banRepository.findForUpdate(communityId, targetUserId)
				.filter(existing -> existing.isActiveAt(now)).filter(CommunityBan::hasEscalatedAppeal)
				.orElseThrow(EscalatedAppealNotFoundException::new);
		ban.rejectEscalation();
		eventPublisher
				.publishEvent(new CommunityBanAppealEscalationRejectedEvent(communityId, targetUserId, adminId, now));
	}

	@Transactional
	void rejectBanAppeal(UUID communityId, UUID requesterId, UUID targetUserId) {
		requireCommunity(communityId);
		requireModerator(communityId, requesterId);
		Instant now = Instant.now();
		CommunityBan ban = banRepository.findForUpdate(communityId, targetUserId)
				.filter(existing -> existing.isActiveAt(now)).filter(CommunityBan::hasPendingAppeal)
				.orElseThrow(BanAppealNotFoundException::new);
		ban.rejectAppeal();
		eventPublisher.publishEvent(new CommunityBanAppealRejectedEvent(communityId, targetUserId, requesterId, now));
	}

	@Transactional(readOnly = true)
	Page<CommunityBanResponse> listBans(UUID communityId, UUID requesterId, Pageable pageable) {
		requireCommunity(communityId);
		requireModerator(communityId, requesterId);
		Page<CommunityBan> page = banRepository.findActiveByCommunityId(communityId, Instant.now(), pageable);
		Set<UUID> userIds = page.getContent().stream().map(CommunityBan::getUserId).collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory.displayNames(userIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(userIds);
		return page.map(ban -> new CommunityBanResponse(ban.getUserId(), names.get(ban.getUserId()),
				avatars.get(ban.getUserId()), ban.getReason(), ban.getBannedBy(), ban.getCreatedAt(),
				ban.getExpiresAt(), ban.getAppealText(), ban.getAppealedAt(), ban.getAppealStatus()));
	}

	private void requireModerator(UUID communityId, UUID requesterId) {
		if (!canModerate(communityId, requesterId)) {
			throw new NotCommunityModeratorException();
		}
	}

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

	@Transactional
	void join(UUID communityId, UUID userId) {
		Community community = requireCommunity(communityId);
		if (banRepository.existsActive(communityId, userId, Instant.now())) {
			throw new BannedFromCommunityException();
		}
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

	@Transactional
	void deleteAccountData(UUID userId) {
		memberRepository.deleteByUserId(userId);
		banRepository.clearBannedBy(userId);
		banRepository.deleteByUserId(userId);
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

	private CommunityResponse toResponse(Community community, long memberCount, CommunityRole myRole) {
		Set<UUID> ownerId = Set.of(community.getOwnerId());
		return toResponse(community, memberCount, myRole, profileDirectory.displayNames(ownerId),
				profileDirectory.avatarUrls(ownerId));
	}

	private CommunityResponse toResponse(Community community, long memberCount, CommunityRole myRole,
			Map<UUID, String> ownerNames, Map<UUID, String> ownerAvatars) {
		UUID ownerId = community.getOwnerId();
		return new CommunityResponse(community.getId(), community.getName(), community.getDescription(), ownerId,
				ownerNames.get(ownerId), ownerAvatars.get(ownerId), memberCount, myRole, community.getCreatedAt());
	}
}
