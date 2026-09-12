package com.songly.moderationlog;

import com.songly.community.CommunityMembership;
import com.songly.moderationlog.dto.ModerationLogEntryResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Zapis i odczyt historii moderacji (ADR 0024).
 *
 * <p>
 * Dziennik jest <b>append-only</b>: nie ma tu żadnej metody zmieniającej wpis.
 * Historia, którą da się poprawić, nie jest historią — a skoro wpisy powstają z
 * decyzji już podjętych, nie ma czego poprawiać.
 */
@Service
class ModerationLogService {

	private final ModerationLogRepository repository;
	private final CommunityMembership communityMembership;

	ModerationLogService(ModerationLogRepository repository, CommunityMembership communityMembership) {
		this.repository = repository;
		this.communityMembership = communityMembership;
	}

	/**
	 * REQUIRES_NEW celowo: wołane z listenerów {@code AFTER_COMMIT}, gdzie
	 * transakcja źródłowa już się zakończyła (ten sam wzorzec co
	 * {@code NotificationService.record} i {@code AnalyticsService.record}).
	 *
	 * <p>
	 * <b>Celowo bez skróconego przeciążenia</b> dla akcji, które nie mają terminu
	 * ani licznika zgłoszeń — wygodniejsze byłoby mieć
	 * {@code record(action, community, subject, actor, occurredAt)}, ale takie
	 * przeciążenie musiałoby zawołać tę metodę <i>na sobie</i>, a samowywołanie
	 * omija proxy Springa i razem z nim {@code REQUIRES_NEW}. Skutek jest cichy:
	 * zapis dołącza się do zakończonej transakcji źródłowej i przepada bez żadnego
	 * błędu, więc w dzienniku brakuje połowy wpisów, a nic tego nie sygnalizuje.
	 * Sprawdzone na własnej skórze przy pisaniu tego modułu.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void record(ModerationAction action, UUID communityId, UUID subjectId, UUID actorId, Instant expiresAt,
			Integer reportCount, Instant occurredAt) {
		repository.save(new ModerationLogEntry(UUID.randomUUID(), action, communityId, subjectId, actorId, expiresAt,
				reportCount, occurredAt));
	}

	/**
	 * Historia jednej społeczności — dla jej moderatorów. Stronicowana, w
	 * odróżnieniu od wykazów stanu (ADR 0021, 0022): tamte są małe z natury, a
	 * dziennik rośnie bez górnego ograniczenia, więc tutaj paginacja z §6 jest
	 * dokładnie na miejscu.
	 */
	@Transactional(readOnly = true)
	Page<ModerationLogEntryResponse> communityLog(UUID communityId, UUID requesterId, Pageable pageable) {
		if (!communityMembership.exists(communityId)) {
			throw new CommunityLogNotFoundException();
		}
		if (!communityMembership.canModerate(communityId, requesterId)) {
			throw new NotCommunityModeratorException();
		}
		return repository.findByCommunityIdOrderByOccurredAtDesc(communityId, pageable)
				.map(ModerationLogService::toResponse);
	}

	/**
	 * Historia całej platformy — dla administratorów (gating rolą jest na prefiksie
	 * {@code /api/v1/admin/**}, ADR 0021). Opcjonalny filtr po osobie odpowiada na
	 * pytanie, którego nie dało się dotąd zadać nigdzie: „co ten człowiek ma za
	 * sobą".
	 */
	@Transactional(readOnly = true)
	Page<ModerationLogEntryResponse> platformLog(UUID subjectId, Pageable pageable) {
		Page<ModerationLogEntry> page = subjectId == null
				? repository.findByOrderByOccurredAtDesc(pageable)
				: repository.findBySubjectIdOrderByOccurredAtDesc(subjectId, pageable);
		return page.map(ModerationLogService::toResponse);
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		repository.deleteBySubjectId(userId);
		repository.clearActor(userId);
	}

	@Transactional
	void deleteCommunityData(UUID communityId) {
		repository.deleteByCommunityId(communityId);
	}

	private static ModerationLogEntryResponse toResponse(ModerationLogEntry entry) {
		return new ModerationLogEntryResponse(entry.getAction(), entry.getCommunityId(), entry.getSubjectId(),
				entry.getActorId(), entry.getExpiresAt(), entry.getReportCount(), entry.getOccurredAt());
	}
}
