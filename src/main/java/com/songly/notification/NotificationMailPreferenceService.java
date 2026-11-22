package com.songly.notification;

import com.songly.notification.dto.MailPreferencesResponse;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationMailPreferenceService {

	private final NotificationMailPreferenceRepository repository;

	NotificationMailPreferenceService(NotificationMailPreferenceRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	Set<UUID> allowedRecipients(Collection<UUID> userIds, MailCategory category) {
		if (userIds.isEmpty()) {
			return Set.of();
		}
		Map<UUID, NotificationMailPreference> preferences = repository.findByUserIdIn(userIds).stream()
				.collect(Collectors.toMap(NotificationMailPreference::getUserId, Function.identity()));
		return userIds.stream().filter(userId -> {
			NotificationMailPreference preference = preferences.get(userId);
			return preference == null || preference.allows(category);
		}).collect(Collectors.toSet());
	}

	@Transactional(readOnly = true)
	MailPreferencesResponse settings(UUID userId) {
		return repository.findById(userId)
				.map(preference -> new MailPreferencesResponse(preference.isDecisionsAboutMe(),
						preference.isModerationQueue()))
				.orElseGet(() -> new MailPreferencesResponse(true, true));
	}

	@Transactional
	void update(UUID userId, boolean decisionsAboutMe, boolean moderationQueue) {
		Instant now = Instant.now();
		repository.findById(userId).ifPresentOrElse(
				preference -> preference.update(decisionsAboutMe, moderationQueue, now),
				() -> repository.save(new NotificationMailPreference(userId, decisionsAboutMe, moderationQueue, now)));
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		repository.deleteByUserId(userId);
	}
}
