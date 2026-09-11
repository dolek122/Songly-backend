package com.songly.community;

import com.songly.community.dto.BanDetailsResponse;
import com.songly.community.dto.CommunityBanResponse;
import com.songly.community.dto.CommunityMemberResponse;
import com.songly.community.dto.CommunityMembershipStatus;
import com.songly.community.dto.CommunityResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CommunityService implements CommunityMembership, CommunityDirectory {

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

	/**
	 * Własny status pytającego — w tym szczegóły <b>własnego</b> bana (ADR 0015).
	 *
	 * <p>
	 * To jedyne miejsce, w którym zbanowany widzi powód i termin. Powiadomienie
	 * jest płaskie (ADR 0012), a mail celowo powodu nie niesie: mail omija blokady
	 * (ADR 0013) i jest kanałem push, więc powód-w-mailu zamieniałby cykl ban/unban
	 * w doręczanie dowolnego tekstu moderatora prosto do cudzej skrzynki pocztowej.
	 * Tu jest odwrotnie — po treść trzeba samemu przyjść.
	 *
	 * <p>
	 * Członkostwo sprawdzane przed banem: po wygaśnięciu bana i powrocie oba
	 * wiersze istnieją naraz (wygasły ban zostaje, ADR 0014), a stan opisuje
	 * członkostwo.
	 */
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
						new BanDetailsResponse(ban.getReason(), ban.getCreatedAt(), ban.getExpiresAt())))
				.orElseGet(() -> new MembershipStatusResponse(CommunityMembershipStatus.NONE, null, null, null));
	}

	/**
	 * Nadaje rolę w społeczności. <b>Wyłącznie właściciel</b> — moderator mogący
	 * rozdawać role mógłby zdegradować właściciela i przejąć społeczność.
	 *
	 * <p>
	 * {@code OWNER} nie jest tu osiągalny: właścicielstwo to nie stopień w tej
	 * samej skali, tylko osobne pole na {@code communities}, i jego przekazanie
	 * jest inną operacją (dziś nieistniejącą). Dopuszczenie go tutaj dałoby dwa
	 * źródła prawdy o tym, kto jest właścicielem.
	 */
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
		membership.changeRole(role);
	}

	/**
	 * Banuje użytkownika: usuwa członkostwo i blokuje ponowne dołączenie.
	 *
	 * <p>
	 * Samo usunięcie z listy członków nic by nie dało — dołączenie jest otwarte i
	 * natychmiastowe (§8), więc zbanowany wróciłby jednym żądaniem. Dopiero wiersz
	 * w {@code community_bans} czyni tę decyzję trwałą.
	 *
	 * <p>
	 * Idempotentne wobec banu <b>aktywnego</b>: ponowny ban tej samej osoby nie
	 * jest błędem, tylko brakiem zmiany — moderator nie powinien dostawać błędu za
	 * to, że ktoś inny zdążył przed nim. Nie zmienia też czasu trwania:
	 * przedłużenie albo skrócenie aktywnego banu to unban + ban, dwie świadome
	 * decyzje zamiast cichej podmiany terminu przy zduplikowanym kliknięciu.
	 *
	 * <p>
	 * Ban <b>wygasły</b> to co innego (ADR 0014): wiersz zostaje w tabeli tylko
	 * dlatego, że klucz główny nie dopuszcza drugiego — ponowny ban nadpisuje go w
	 * całości i publikuje nowe zdarzenie, bo z perspektywy zbanowanego to nowa
	 * decyzja, o której trzeba zawiadomić.
	 *
	 * @param durationDays
	 *            {@code null} = ban bezterminowy
	 */
	@Transactional
	void ban(UUID communityId, UUID requesterId, UUID targetUserId, String reason, Integer durationDays) {
		Community community = requireCommunity(communityId);
		requireModerator(communityId, requesterId);
		if (community.getOwnerId().equals(targetUserId)) {
			throw new CannotModerateOwnerException();
		}
		// Moderator nie zbanuje moderatora — inaczej dwoje moderatorów mogłoby
		// wzajemnie wyrzucić się ze społeczności, a rozstrzygnięcie zależałoby od
		// tego, kto pierwszy kliknął.
		if (!requesterId.equals(community.getOwnerId()) && canModerate(communityId, targetUserId)) {
			throw new CannotModerateModeratorException();
		}
		memberRepository.findById(new CommunityMemberId(communityId, targetUserId)).ifPresent(memberRepository::delete);
		Instant now = Instant.now();
		Instant expiresAt = durationDays == null ? null : now.plus(Duration.ofDays(durationDays));
		CommunityBan existing = banRepository.findById(new CommunityBanId(communityId, targetUserId)).orElse(null);
		// Zdarzenie leci tylko przy banie, który zmienia stan z „niezbanowany" na
		// „zbanowany" — czyli przy pierwszym i przy nadpisaniu wygasłego. Powtórzone
		// kliknięcie na aktywnym banie nie dokłada powiadomienia o tym samym.
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

	/** Idempotentne — zdjęcie nieistniejącego banu nie jest błędem. */
	@Transactional
	void unban(UUID communityId, UUID requesterId, UUID targetUserId) {
		requireCommunity(communityId);
		requireModerator(communityId, requesterId);
		banRepository.findById(new CommunityBanId(communityId, targetUserId)).ifPresent(banRepository::delete);
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
				ban.getExpiresAt()));
	}

	private void requireModerator(UUID communityId, UUID requesterId) {
		if (!canModerate(communityId, requesterId)) {
			throw new NotCommunityModeratorException();
		}
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
		// Bez tego sprawdzenia ban byłby wyłącznie usunięciem z listy: dołączenie
		// jest otwarte i natychmiastowe (§8), więc zbanowany wracałby jednym
		// żądaniem, a moderacja sprowadzałaby się do wyścigu na kliknięcia.
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
		// Bany NAŁOŻONE przez tego usera zostają — należą do społeczności, nie do
		// niego. Gdyby znikały razem z jego kontem, odejście moderatora po cichu
		// wpuszczałoby z powrotem wszystkich, których zbanował. Gubimy tylko autora,
		// bo klucz obcy do users nie ma jak przetrwać.
		banRepository.clearBannedBy(userId);
		// Bany NA tego usera znikają razem z kontem — nie ma już kogo trzymać za
		// drzwiami, a wiersz i tak ma klucz obcy do users.
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
