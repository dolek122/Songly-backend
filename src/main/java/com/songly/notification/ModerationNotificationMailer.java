package com.songly.notification;

import com.songly.common.mail.MailSender;
import com.songly.community.CommunityBanAppealAcceptedEvent;
import com.songly.community.CommunityBanAppealEscalatedEvent;
import com.songly.community.CommunityBanAppealEscalationAcceptedEvent;
import com.songly.community.CommunityBanAppealEscalationRejectedEvent;
import com.songly.community.CommunityBanAppealRejectedEvent;
import com.songly.community.CommunityBanAppealSubmittedEvent;
import com.songly.community.CommunityDirectory;
import com.songly.community.CommunityMemberBannedEvent;
import com.songly.community.CommunityMembership;
import com.songly.content.CommunityCommentRemovedEvent;
import com.songly.content.CommunityPostRemovedEvent;
import com.songly.content.PostReportDismissedEvent;
import com.songly.content.PostReportedEvent;
import com.songly.identity.AdminDirectory;
import com.songly.identity.SuspendedAccounts;
import com.songly.identity.UserEmailDirectory;
import com.songly.identity.UserSuspendedEvent;
import com.songly.identity.UserSuspensionAppealAcceptedEvent;
import com.songly.identity.UserSuspensionAppealRejectedEvent;
import com.songly.identity.UserSuspensionAppealSubmittedEvent;
import com.songly.identity.UserSuspensionLiftedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@EnableConfigurationProperties(ModerationMailProperties.class)
class ModerationNotificationMailer {

	private static final Logger log = LoggerFactory.getLogger(ModerationNotificationMailer.class);

	private static final String DELETED_COMMUNITY = "(usunięta społeczność)";

	private static final String MAIL_SUPPRESSED_METRIC = "songly.moderation.mail.suppressed";

	private final MailSender mailSender;
	private final UserEmailDirectory userEmailDirectory;
	private final CommunityDirectory communityDirectory;
	private final CommunityMembership communityMembership;
	private final StringRedisTemplate redisTemplate;
	private final ModerationMailProperties properties;
	private final MeterRegistry meterRegistry;
	private final NotificationMailPreferenceService mailPreferences;
	private final AdminDirectory adminDirectory;
	private final SuspendedAccounts suspendedAccounts;

	ModerationNotificationMailer(MailSender mailSender, UserEmailDirectory userEmailDirectory,
			CommunityDirectory communityDirectory, CommunityMembership communityMembership,
			StringRedisTemplate redisTemplate, ModerationMailProperties properties, MeterRegistry meterRegistry,
			NotificationMailPreferenceService mailPreferences, AdminDirectory adminDirectory,
			SuspendedAccounts suspendedAccounts) {
		this.mailSender = mailSender;
		this.userEmailDirectory = userEmailDirectory;
		this.communityDirectory = communityDirectory;
		this.communityMembership = communityMembership;
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
		this.mailPreferences = mailPreferences;
		this.adminDirectory = adminDirectory;
		this.suspendedAccounts = suspendedAccounts;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityMemberBanned(CommunityMemberBannedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.userId()), event.moderatorId(), MailCategory.DECISION_ABOUT_ME,
				"Songly — ban w społeczności „%s”".formatted(community), """
						Cześć!

						Moderacja społeczności „%s” w Songly zablokowała Ci możliwość
						publikowania i komentowania w niej. Nadal możesz czytać jej treści.

						%s

						Szczegóły znajdziesz w aplikacji, w skrzynce powiadomień.

						Zespół Songly""".formatted(community, banTerm(event)));
	}

	private static String banTerm(CommunityMemberBannedEvent event) {
		if (event.expiresAt() == null) {
			return "Ban jest bezterminowy.";
		}
		long days = Duration.between(event.occurredAt(), event.expiresAt()).toDays();
		return "Ban jest czasowy — wygasa za %d %s i wtedy możesz wrócić.".formatted(days, days == 1 ? "dzień" : "dni");
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onUserSuspended(UserSuspendedEvent event) {
		sendTo(Set.of(event.userId()), null, MailCategory.DECISION_ABOUT_ME, "Songly — konto zawieszone", """
				Cześć!

				Twoje konto w Songly zostało zawieszone i na czas zawieszenia nie
				możesz z niego korzystać.

				%s

				Powód poznasz w aplikacji — zawieszone konto ma dostęp do informacji o
				swoim zawieszeniu oraz do usunięcia konta.

				Zespół Songly""".formatted(suspensionTerm(event)));
	}

	private static String suspensionTerm(UserSuspendedEvent event) {
		if (event.expiresAt() == null) {
			return "Zawieszenie jest bezterminowe.";
		}
		long days = Duration.between(event.occurredAt(), event.expiresAt()).toDays();
		return "Zawieszenie jest czasowe — wygasa za %d %s i wtedy konto wróci samo.".formatted(days,
				days == 1 ? "dzień" : "dni");
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onSuspensionAppealSubmitted(UserSuspensionAppealSubmittedEvent event) {
		sendTo(adminDirectory.adminIds(), event.userId(), MailCategory.MODERATION_QUEUE,
				"Songly — odwołanie od zawieszenia konta", """
						Cześć!

						Zawieszony użytkownik odwołał się od decyzji. Treść odwołania
						znajdziesz w aplikacji, w wykazie zawieszonych kont.

						Odwołanie jest jedno na zawieszenie, więc kolejnych wiadomości w tej
						sprawie nie będzie.

						Zespół Songly""");
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onSuspensionAppealAccepted(UserSuspensionAppealAcceptedEvent event) {
		sendTo(Set.of(event.userId()), null, MailCategory.DECISION_ABOUT_ME, "Songly — Twoje odwołanie zostało uznane",
				"""
						Cześć!

						Uznaliśmy Twoje odwołanie — zawieszenie konta zostało zdjęte i
						możesz znów korzystać z Songly normalnie.

						Zespół Songly""");
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onSuspensionAppealRejected(UserSuspensionAppealRejectedEvent event) {
		sendTo(Set.of(event.userId()), null, MailCategory.DECISION_ABOUT_ME,
				"Songly — Twoje odwołanie zostało odrzucone", """
						Cześć!

						Rozpatrzyliśmy Twoje odwołanie i zawieszenie konta pozostaje w mocy.
						Ta decyzja jest ostateczna dla tego zawieszenia.

						Zespół Songly""");
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onUserSuspensionLifted(UserSuspensionLiftedEvent event) {
		sendTo(Set.of(event.userId()), null, MailCategory.DECISION_ABOUT_ME, "Songly — zawieszenie konta zdjęte", """
				Cześć!

				Zawieszenie Twojego konta w Songly zostało zdjęte — możesz znów
				korzystać z aplikacji normalnie.

				Zespół Songly""");
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityPostRemoved(CommunityPostRemovedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.postAuthorId()), event.moderatorId(), MailCategory.DECISION_ABOUT_ME,
				"Songly — Twój post w społeczności „%s” został usunięty".formatted(community), """
						Cześć!

						Moderacja społeczności „%s” w Songly usunęła Twój post.

						Szczegóły znajdziesz w aplikacji, w skrzynce powiadomień.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommunityCommentRemoved(CommunityCommentRemovedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.commentAuthorId()), event.moderatorId(), MailCategory.DECISION_ABOUT_ME,
				"Songly — Twój komentarz w społeczności „%s” został usunięty".formatted(community), """
						Cześć!

						Moderacja społeczności „%s” w Songly usunęła Twój komentarz.

						Szczegóły znajdziesz w aplikacji, w skrzynce powiadomień.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostReported(PostReportedEvent event) {
		String community = communityName(event.communityId());
		sendToModerators(event.communityId(), "report", event.reporterId(),
				"Songly — nowe zgłoszenie w społeczności „%s”".formatted(community), """
						Cześć!

						W społeczności „%s” w Songly pojawiło się zgłoszenie posta.
						Zajrzyj do kolejki zgłoszeń w aplikacji, żeby je rozpatrzyć.

						Kolejne zgłoszenia tego samego posta nie wygenerują następnych
						wiadomości — ich liczbę zobaczysz przy poście w kolejce.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanAppealSubmitted(CommunityBanAppealSubmittedEvent event) {
		String community = communityName(event.communityId());
		sendToModerators(event.communityId(), "appeal", event.userId(),
				"Songly — odwołanie od bana w społeczności „%s”".formatted(community), """
						Cześć!

						W społeczności „%s” w Songly ktoś odwołał się od bana.
						Treść odwołania znajdziesz w aplikacji, na liście banów.

						Odwołanie jest jedno na ban, więc kolejnych wiadomości w tej
						sprawie nie będzie.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanAppealAccepted(CommunityBanAppealAcceptedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.userId()), event.moderatorId(), MailCategory.DECISION_ABOUT_ME,
				"Songly — Twoje odwołanie w społeczności „%s” zostało uznane".formatted(community), """
						Cześć!

						Moderacja społeczności „%s” w Songly uznała Twoje odwołanie.
						Ban został zdjęty — możesz wrócić do społeczności.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanAppealRejected(CommunityBanAppealRejectedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.userId()), event.moderatorId(), MailCategory.DECISION_ABOUT_ME,
				"Songly — Twoje odwołanie w społeczności „%s” zostało odrzucone".formatted(community), """
						Cześć!

						Moderacja społeczności „%s” w Songly rozpatrzyła Twoje odwołanie
						i uznała, że ban pozostaje w mocy. Ta decyzja jest ostateczna dla
						tego bana.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanAppealEscalated(CommunityBanAppealEscalatedEvent event) {
		String community = communityName(event.communityId());
		sendTo(adminDirectory.adminIds(), event.userId(), MailCategory.MODERATION_QUEUE,
				"Songly — odwołanie przekazane do administracji", """
						Cześć!

						Zbanowany w społeczności „%s” przekazał swoje odwołanie do
						administracji platformy, bo moderacja społeczności je odrzuciła.
						Treść znajdziesz w aplikacji, w kolejce spraw drugiej instancji.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanEscalationAccepted(CommunityBanAppealEscalationAcceptedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.userId()), event.adminId(), MailCategory.DECISION_ABOUT_ME,
				"Songly — administracja uchyliła ban w społeczności „%s”".formatted(community), """
						Cześć!

						Administracja Songly rozpatrzyła Twoje odwołanie i uchyliła ban w
						społeczności „%s”. Możesz do niej wrócić.

						Zespół Songly""".formatted(community));
		sendToModerators(event.communityId(), "escalation", event.userId(),
				"Songly — administracja uchyliła ban w społeczności „%s”".formatted(community), """
						Cześć!

						Administracja Songly rozpatrzyła odwołanie od banu w społeczności
						„%s” i uchyliła tę decyzję, więc zbanowana osoba wraca do
						społeczności.

						Szczegóły sprawy znajdziesz w historii moderacji w aplikacji.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanEscalationRejected(CommunityBanAppealEscalationRejectedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.userId()), event.adminId(), MailCategory.DECISION_ABOUT_ME,
				"Songly — administracja utrzymała ban w społeczności „%s”".formatted(community), """
						Cześć!

						Administracja Songly rozpatrzyła Twoje odwołanie od banu w
						społeczności „%s” i utrzymała decyzję moderacji. To rozstrzygnięcie
						jest ostateczne dla tego banu.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostReportDismissed(PostReportDismissedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.reporterId()), event.moderatorId(), MailCategory.DECISION_ABOUT_ME,
				"Songly — Twoje zgłoszenie zostało rozpatrzone", """
						Cześć!

						Moderacja społeczności „%s” w Songly obejrzała zgłoszony przez Ciebie
						post i uznała, że może zostać.

						Dziękujemy za zgłoszenie — to dzięki nim moderacja wie, czemu się
						przyjrzeć, także wtedy, gdy decyzja jest inna, niż się spodziewasz.

						Zespół Songly""".formatted(community));
	}

	private void sendToModerators(UUID communityId, String kind, UUID actorId, String subject, String body) {
		if (!claimModeratorMailSlot(communityId, kind)) {
			meterRegistry.counter(MAIL_SUPPRESSED_METRIC, "kind", kind).increment();
			log.debug("Mail do moderatorów społeczności {} ({}) wyciszony odstępem", communityId, kind);
			return;
		}
		Set<UUID> suspended = suspendedAccounts.suspendedUserIds();
		Set<UUID> moderators = communityMembership.moderatorIds(communityId).stream()
				.filter(moderatorId -> !suspended.contains(moderatorId)).collect(Collectors.toSet());
		sendTo(moderators, actorId, MailCategory.MODERATION_QUEUE, subject, body);
	}

	private boolean claimModeratorMailSlot(UUID communityId, String kind) {
		try {
			Boolean claimed = redisTemplate.opsForValue().setIfAbsent("moderation-mail:" + kind + ":" + communityId,
					"1", properties.getModeratorInterval());
			return claimed == null || claimed;
		} catch (RuntimeException ex) {
			log.warn("Wyciszanie maili moderacyjnych wyłączone dla tej wiadomości — Redis nie odpowiada: {}",
					ex.toString());
			return true;
		}
	}

	private void sendTo(Set<UUID> recipientIds, UUID actorId, MailCategory category, String subject, String body) {
		Set<UUID> recipients = recipientIds.stream().filter(id -> !id.equals(actorId)).collect(Collectors.toSet());
		recipients = mailPreferences.allowedRecipients(recipients, category);
		Map<UUID, String> emails = userEmailDirectory.emailsOf(recipients);
		emails.forEach((recipientId, email) -> {
			try {
				mailSender.send(email, subject, body);
			} catch (RuntimeException ex) {
				log.error("Nie udało się wysłać maila o decyzji moderacyjnej do użytkownika {}", recipientId, ex);
			}
		});
	}

	private String communityName(UUID communityId) {
		return communityDirectory.nameOf(communityId).orElse(DELETED_COMMUNITY);
	}
}
