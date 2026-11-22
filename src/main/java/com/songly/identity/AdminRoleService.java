package com.songly.identity;

import com.songly.identity.dto.AdminListResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminRoleService implements AdminDirectory {

	private static final Logger log = LoggerFactory.getLogger(AdminRoleService.class);

	private static final String ROLE_CHANGES_METRIC = "songly.admin.role.changes";

	private final UserRepository userRepository;
	private final MeterRegistry meterRegistry;
	private final ApplicationEventPublisher eventPublisher;

	AdminRoleService(UserRepository userRepository, MeterRegistry meterRegistry,
			ApplicationEventPublisher eventPublisher) {
		this.userRepository = userRepository;
		this.meterRegistry = meterRegistry;
		this.eventPublisher = eventPublisher;
	}

	@Transactional(readOnly = true)
	AdminListResponse admins() {
		return new AdminListResponse(
				userRepository.findByAdminTrueOrderByCreatedAtAsc().stream().map(User::getId).toList());
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> adminIds() {
		return userRepository.findByAdminTrueOrderByCreatedAtAsc().stream().map(User::getId)
				.collect(Collectors.toSet());
	}

	@Transactional
	void grant(UUID targetUserId, UUID requesterId) {
		User target = userRepository.findById(targetUserId).orElseThrow(AdminUserNotFoundException::new);
		if (target.isAdmin()) {
			return;
		}
		target.changeAdmin(true);
		log.warn("Nadano uprawnienia administratora użytkownikowi {} (wykonał {})", targetUserId, requesterId);
		meterRegistry.counter(ROLE_CHANGES_METRIC, "action", "granted").increment();
		eventPublisher.publishEvent(new AdminRoleChangedEvent(targetUserId, requesterId, true, Instant.now()));
	}

	@Transactional
	void revoke(UUID targetUserId, UUID requesterId) {
		if (targetUserId.equals(requesterId)) {
			throw new CannotRevokeOwnAdminException();
		}
		User target = userRepository.findById(targetUserId).orElseThrow(AdminUserNotFoundException::new);
		if (!target.isAdmin()) {
			return;
		}
		target.changeAdmin(false);
		log.warn("Odebrano uprawnienia administratora użytkownikowi {} (wykonał {})", targetUserId, requesterId);
		meterRegistry.counter(ROLE_CHANGES_METRIC, "action", "revoked").increment();
		eventPublisher.publishEvent(new AdminRoleChangedEvent(targetUserId, requesterId, false, Instant.now()));
	}
}
