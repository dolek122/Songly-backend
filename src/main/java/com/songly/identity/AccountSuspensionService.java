package com.songly.identity;

import com.songly.identity.dto.SuspendedAccountResponse;
import com.songly.identity.dto.SuspensionResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Globalne zawieszanie kont (ADR 0022) — pierwsza sankcja na poziomie
 * platformy, a nie pojedynczej społeczności.
 *
 * <p>
 * Egzekwowanie nie siedzi tutaj: całość robi {@code JwtAuthenticationFilter},
 * nadając zawieszonemu kontu wyłącznie {@code ROLE_SUSPENDED}, podczas gdy
 * reszta API wymaga {@code ROLE_USER}. Ten serwis tylko zmienia stan, który ten
 * filtr czyta przy każdym żądaniu.
 */
@Service
class AccountSuspensionService {

	private static final Logger log = LoggerFactory.getLogger(AccountSuspensionService.class);

	/**
	 * W Prometheusie: {@code songly_admin_suspensions_total{action="..."}} — jak
	 * licznik zmian roli (ADR 0021), seria rzadka, w której każdy przyrost jest
	 * wart zauważenia.
	 */
	private static final String SUSPENSIONS_METRIC = "songly.admin.suspensions";

	private final UserRepository userRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;

	AccountSuspensionService(UserRepository userRepository, ApplicationEventPublisher eventPublisher,
			MeterRegistry meterRegistry) {
		this.userRepository = userRepository;
		this.eventPublisher = eventPublisher;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Zawiesza konto. Nadpisuje istniejące zawieszenie (zmiana powodu albo terminu
	 * to ta sama decyzja podjęta na nowo), ale zdarzenie publikuje <b>tylko przy
	 * przejściu z konta aktywnego na zawieszone</b> — inaczej poprawka literówki w
	 * powodzie wysyłałaby drugi mail o tym samym.
	 *
	 * <p>
	 * Dwa zakazy, oba przeniesione wprost z reguł moderacji społeczności (ADR
	 * 0011): <b>nie siebie</b> — administrator zawieszający samego siebie traciłby
	 * dostęp do endpointu, którym mógłby to cofnąć; <b>nie innego
	 * administratora</b> — inaczej dwóch adminów mogłoby się wzajemnie wyłączyć, a
	 * rozstrzygałoby to, kto pierwszy kliknął. Zawieszenie admina wymaga więc
	 * najpierw odebrania mu roli (ADR 0021), czyli świadomych dwóch kroków.
	 */
	@Transactional
	void suspend(UUID targetUserId, UUID requesterId, String reason, Integer durationDays) {
		if (targetUserId.equals(requesterId)) {
			throw new CannotSuspendSelfException();
		}
		User target = userRepository.findById(targetUserId).orElseThrow(AdminUserNotFoundException::new);
		if (target.isAdmin()) {
			throw new CannotSuspendAdminException();
		}
		Instant now = Instant.now();
		boolean wasActive = !target.isSuspendedAt(now);
		target.suspend(reason, now, durationDays == null ? null : now.plus(Duration.ofDays(durationDays)));
		log.warn("Zawieszono konto {} (wykonał {}, bezterminowo: {})", targetUserId, requesterId, durationDays == null);
		meterRegistry.counter(SUSPENSIONS_METRIC, "action", "suspended").increment();
		if (wasActive) {
			eventPublisher.publishEvent(new UserSuspendedEvent(targetUserId, target.getSuspensionExpiresAt(), now));
		}
	}

	/** Idempotentne — zdjęcie zawieszenia z konta aktywnego nie jest błędem. */
	@Transactional
	void lift(UUID targetUserId, UUID requesterId) {
		User target = userRepository.findById(targetUserId).orElseThrow(AdminUserNotFoundException::new);
		if (target.getSuspendedAt() == null) {
			return;
		}
		boolean wasSuspended = target.isSuspendedAt(Instant.now());
		target.liftSuspension();
		log.warn("Zdjęto zawieszenie z konta {} (wykonał {})", targetUserId, requesterId);
		meterRegistry.counter(SUSPENSIONS_METRIC, "action", "lifted").increment();
		// Konto z wygasłym zawieszeniem i tak działało, więc nie ma o czym
		// zawiadamiać — sprzątamy wtedy martwe pola, nie zmieniamy stanu.
		if (wasSuspended) {
			eventPublisher.publishEvent(new UserSuspensionLiftedEvent(targetUserId, Instant.now()));
		}
	}

	/**
	 * Wykaz <b>aktywnych</b> zawieszeń — wygasłe nie liczą się nigdzie (ADR 0014),
	 * więc ich obecność na liście sugerowałaby sankcję, której nie ma. Bez
	 * paginacji, z tego samego powodu co wykaz administratorów (ADR 0021): to lista
	 * do przeglądu, a nie strumień danych.
	 */
	@Transactional(readOnly = true)
	List<SuspendedAccountResponse> suspendedAccounts() {
		Instant now = Instant.now();
		return userRepository.findBySuspendedAtIsNotNullOrderBySuspendedAtDesc().stream()
				.filter(user -> user.isSuspendedAt(now)).map(user -> new SuspendedAccountResponse(user.getId(),
						user.getSuspensionReason(), user.getSuspendedAt(), user.getSuspensionExpiresAt()))
				.toList();
	}

	/**
	 * Własny stan zawieszenia. Konto aktywne dostaje {@code suspended=false} z
	 * pustymi polami, a nie 404 — klient pyta o ten endpoint po każdym
	 * {@code ACCOUNT_SUSPENDED} i nie powinien musieć rozróżniać „nie jesteś
	 * zawieszony" od „nie ma takiego zasobu".
	 */
	@Transactional(readOnly = true)
	SuspensionResponse ownSuspension(UUID userId) {
		Instant now = Instant.now();
		return userRepository.findById(userId).filter(user -> user.isSuspendedAt(now))
				.map(user -> new SuspensionResponse(true, user.getSuspensionReason(), user.getSuspendedAt(),
						user.getSuspensionExpiresAt()))
				.orElseGet(() -> new SuspensionResponse(false, null, null, null));
	}
}
