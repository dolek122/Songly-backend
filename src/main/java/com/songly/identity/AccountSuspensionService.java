package com.songly.identity;

import com.songly.identity.dto.SuspendedAccountResponse;
import com.songly.identity.dto.SuspensionResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountSuspensionService implements SuspendedAccounts {

	private static final Logger log = LoggerFactory.getLogger(AccountSuspensionService.class);

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

	@Transactional
	void suspend(UUID targetUserId, UUID requesterId, String reason, Integer durationDays) {
		if (targetUserId.equals(requesterId)) {
			throw new CannotSuspendSelfException();
		}
		User target = userRepository.findByIdForUpdate(targetUserId).orElseThrow(AdminUserNotFoundException::new);
		if (target.isAdmin()) {
			throw new CannotSuspendAdminException();
		}
		Instant now = Instant.now();
		boolean wasActive = !target.isSuspendedAt(now);
		target.suspend(reason, now, durationDays == null ? null : now.plus(Duration.ofDays(durationDays)));
		if (wasActive) {
			target.resetAppeal();
		}
		log.warn("Zawieszono konto {} (wykonał {}, bezterminowo: {})", targetUserId, requesterId, durationDays == null);
		meterRegistry.counter(SUSPENSIONS_METRIC, "action", "suspended").increment();
		if (wasActive) {
			eventPublisher.publishEvent(
					new UserSuspendedEvent(targetUserId, requesterId, target.getSuspensionExpiresAt(), now));
		}
	}

	@Transactional
	void lift(UUID targetUserId, UUID requesterId) {
		User target = userRepository.findByIdForUpdate(targetUserId).orElseThrow(AdminUserNotFoundException::new);
		if (target.getSuspendedAt() == null) {
			return;
		}
		Instant now = Instant.now();
		boolean wasSuspended = target.isSuspendedAt(now);
		boolean acceptsAppeal = wasSuspended && target.hasPendingAppeal();
		target.liftSuspension();
		log.warn("Zdjęto zawieszenie z konta {} (wykonał {}, uznanie odwołania: {})", targetUserId, requesterId,
				acceptsAppeal);
		meterRegistry.counter(SUSPENSIONS_METRIC, "action", "lifted").increment();
		if (acceptsAppeal) {
			eventPublisher.publishEvent(new UserSuspensionAppealAcceptedEvent(targetUserId, requesterId, now));
		} else if (wasSuspended) {
			eventPublisher.publishEvent(new UserSuspensionLiftedEvent(targetUserId, requesterId, now));
		}
	}

	@Transactional(readOnly = true)
	List<SuspendedAccountResponse> suspendedAccounts() {
		Instant now = Instant.now();
		return userRepository.findBySuspendedAtIsNotNullOrderBySuspendedAtDesc().stream()
				.filter(user -> user.isSuspendedAt(now))
				.map(user -> new SuspendedAccountResponse(user.getId(), user.getSuspensionReason(),
						user.getSuspendedAt(), user.getSuspensionExpiresAt(), user.getSuspensionAppealText(),
						user.getSuspensionAppealedAt(), user.getSuspensionAppealStatus()))
				.toList();
	}

	@Transactional
	void appeal(UUID userId, String text) {
		Instant now = Instant.now();
		User user = userRepository.findByIdForUpdate(userId).filter(candidate -> candidate.isSuspendedAt(now))
				.orElseThrow(NoSuspensionToAppealException::new);
		if (user.hasAppeal()) {
			throw new SuspensionAppealAlreadySubmittedException();
		}
		user.submitAppeal(text, now);
		eventPublisher.publishEvent(new UserSuspensionAppealSubmittedEvent(userId, now));
	}

	@Transactional
	void rejectAppeal(UUID targetUserId, UUID requesterId) {
		Instant now = Instant.now();
		User target = userRepository.findByIdForUpdate(targetUserId).filter(user -> user.isSuspendedAt(now))
				.filter(User::hasPendingAppeal).orElseThrow(SuspensionAppealNotFoundException::new);
		target.rejectAppeal();
		log.warn("Odrzucono odwołanie od zawieszenia konta {} (wykonał {})", targetUserId, requesterId);
		meterRegistry.counter(SUSPENSIONS_METRIC, "action", "appeal-rejected").increment();
		eventPublisher.publishEvent(new UserSuspensionAppealRejectedEvent(targetUserId, requesterId, now));
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> suspendedUserIds() {
		return Set.copyOf(userRepository.findSuspendedIds(Instant.now()));
	}

	@Transactional(readOnly = true)
	SuspensionResponse ownSuspension(UUID userId) {
		Instant now = Instant.now();
		return userRepository.findById(userId).filter(user -> user.isSuspendedAt(now))
				.map(user -> new SuspensionResponse(true, user.getSuspensionReason(), user.getSuspendedAt(),
						user.getSuspensionExpiresAt(), user.getSuspensionAppealStatus(),
						user.getSuspensionAppealedAt()))
				.orElseGet(() -> new SuspensionResponse(false, null, null, null, null, null));
	}
}
