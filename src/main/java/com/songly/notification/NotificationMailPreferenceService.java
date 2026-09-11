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

/**
 * Preferencje kanału e-mail (ADR 0020).
 *
 * <p>
 * ADR 0013 zostawił świadomy brak opt-outu i wskazał mailer jako jedyne miejsce
 * do podpięcia, gdy preferencje powstaną. Powstały — i rządzą <b>wyłącznie
 * pocztą</b>: skrzynka w aplikacji zostaje kompletna, bo decyzji moderacyjnej
 * nie da się odmówić (ADR 0012), a ścieżka odwoławcza (ADR 0017) traciłaby
 * sens, gdyby dało się nie dowiedzieć o własnym banie.
 */
@Service
class NotificationMailPreferenceService {

	private final NotificationMailPreferenceRepository repository;

	NotificationMailPreferenceService(NotificationMailPreferenceRepository repository) {
		this.repository = repository;
	}

	/**
	 * Odsiewa adresatów, którzy tej kategorii maili nie chcą. Brak wiersza to zgoda
	 * — ustawienie jest opt-outem, bo te maile są konsekwencją korzystania z
	 * usługi, nie marketingiem (ADR 0013).
	 *
	 * <p>
	 * Filtrowanie jest <b>per adresat</b>, a nie „wyślij albo nie": mail o
	 * zgłoszeniu idzie do wszystkich moderatorów naraz, więc jeden wypisany nie
	 * może wyciszyć wysyłki pozostałym.
	 */
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

	/**
	 * Brak wiersza czytamy jako domyślne „oba włączone", bez zapisu przy odczycie.
	 */
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

	/**
	 * Tabela ma FK do {@code users}, więc kasowanie konta musi ją posprzątać —
	 * inaczej {@code DELETE} na użytkowniku odbija się o klucz obcy (Claude.md §9).
	 */
	@Transactional
	void deleteAccountData(UUID userId) {
		repository.deleteByUserId(userId);
	}
}
