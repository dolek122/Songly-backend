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

/**
 * Drugi kanał powiadomień o decyzjach moderacyjnych (ADR 0013): e-mail obok
 * skrzynki w aplikacji.
 *
 * <p>
 * Skrzynka domyka pętlę tylko dla kogoś, kto do aplikacji wejdzie — a ADR 0012
 * powstał właśnie dlatego, że kolejka zgłoszeń czekała na moderatora, który sam
 * z siebie nie miał powodu zajrzeć. Mail dosięga też nieobecnych.
 *
 * <p>
 * <b>Mailem wychodzą wyłącznie typy z {@link NotificationType#MODERATION} — i
 * to nie jest kwestia oszczędności.</b> Skrzynka ukrywa powiadomienia od
 * zablokowanych <i>przy odczycie</i> (Claude.md §9); maila nie da się ukryć po
 * fakcie, więc mailowanie zwykłej aktywności omijałoby filtr blokad bocznymi
 * drzwiami. Typy moderacyjne przechodzą przez ten filtr zawsze, więc dla nich —
 * i tylko dla nich — oba kanały mówią to samo.
 *
 * <p>
 * Osobny listener, nie dopisek w {@code NotificationEventListener}: kanały mają
 * być niezależne, więc awaria zapisu do skrzynki nie może zatrzymać maila ani
 * odwrotnie. Ograniczenie liczby zdarzeń (tylko pierwsze zgłoszenie, tylko nowy
 * ban — ADR 0012) ten kanał dziedziczy za darmo, bo bramka jest u
 * publikującego.
 *
 * <p>
 * Ta niezależność kanałów jest dziś warunkiem tego, żeby preferencje poczty
 * (ADR 0020) w ogóle mogły istnieć: wyłączenie maila nie rusza skrzynki, więc
 * decyzja moderacyjna nadal dociera — tylko kanałem, po który trzeba przyjść.
 */
@Component
@EnableConfigurationProperties(ModerationMailProperties.class)
class ModerationNotificationMailer {

	private static final Logger log = LoggerFactory.getLogger(ModerationNotificationMailer.class);

	/**
	 * Nazwa społeczności skasowanej między commitem a listenerem — praktycznie
	 * niemożliwe w jednym żądaniu, ale kod musi coś wstawić w treść.
	 */
	private static final String DELETED_COMMUNITY = "(usunięta społeczność)";

	/**
	 * W Prometheusie:
	 * {@code songly_moderation_mail_suppressed_total{kind="report|appeal"}} (ADR
	 * 0019) — ile maili do moderatorów przepadło na odstępie z ADR 0018.
	 */
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

	/**
	 * Termin banu musi dotrzeć do zbanowanego: ban czasowy, który wygląda na
	 * dożywotni, mija się z celem — „tydzień przerwy" działa tylko na kimś, kto
	 * wie, że to tydzień. Skrzynka w aplikacji tego nie przenosi (płaski model, ADR
	 * 0012), więc mail jest dziś jedynym kanałem z tą informacją.
	 */
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

	/**
	 * Zawieszenie konta (ADR 0022) — jedyny mail tego mailera bez społeczności, bo
	 * to sankcja platformy, nie decyzja moderacji społecznościowej.
	 *
	 * <p>
	 * Mail jest tu <b>jedynym</b> kanałem powiadomienia: zawieszone konto nie ma
	 * dostępu do skrzynki w aplikacji (dostaje wyłącznie {@code ROLE_SUSPENDED}),
	 * więc zapis w skrzynce byłby informacją zamkniętą przed adresatem. Bez powodu,
	 * z terminem — dokładnie jak przy banie (ADR 0014 i 0015).
	 */
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

	/**
	 * Do <b>wszystkich administratorów</b> (ADR 0023) — wykaz zawieszeń jest
	 * jednocześnie kolejką odwołań, a kolejka bez powiadomienia to narzędzie,
	 * którego nic nie uruchamia. Tu jest to ostrzejsze niż przy zgłoszeniach
	 * społecznościowych: odwołanie przychodzi od użytkownika, więc bez maila nikt z
	 * administracji nie ma skąd o nim wiedzieć.
	 *
	 * <p>
	 * Bez odstępu wyciszającego (ADR 0018): odwołanie jest jedno na zawieszenie, a
	 * zawieszenia nakłada ręcznie administrator, więc wolumen jest ograniczony
	 * strukturalnie, nie czasowo. Bez treści odwołania — tekst od użytkownika nie
	 * jedzie kanałem push (ADR 0015).
	 */
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

	/**
	 * Ta sama kategoria i ten sam kształt co przy usuniętym poście — bez treści
	 * komentarza, której i tak już nie ma, i bez wskazania moderatora.
	 */
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

	/**
	 * To jest mail, dla którego ten kanał powstał: zgłoszenie w społeczności,
	 * której moderator akurat nie odwiedza, leżało w kolejce bezterminowo — a
	 * powiadomienie w aplikacji widzi tylko ktoś, kto do aplikacji wszedł.
	 */
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

	/**
	 * Bez treści odwołania — celowo, jak przy powodzie bana (ADR 0015): tekst od
	 * użytkownika nie jedzie kanałem push. Mail mówi, że jest po co zajrzeć; treść
	 * czeka na liście banów.
	 */
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

	/**
	 * Druga instancja (ADR 0028). Do <b>administracji platformy</b>, bo nie ma w
	 * aplikacji kolejki, do której zaglądałaby sama z siebie — dokładnie ten sam
	 * argument co przy odwołaniach od zawieszenia (ADR 0023). Bez treści odwołania:
	 * tekst zbanowanego nie jedzie kanałem push (ADR 0015), czeka w kolejce spraw.
	 */
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
		// Moderatorzy muszą się dowiedzieć, że ich decyzja została uchylona — inaczej
		// zbanowany wraca do społeczności bez żadnego wyjaśnienia i wygląda to na
		// awarię (ten sam argument, od którego zaczął się ADR 0012).
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

	/**
	 * Rozsyłka do <b>wszystkich moderatorów</b> społeczności, wyciszana odstępem
	 * per społeczność i rodzaj sprawy (ADR 0018).
	 *
	 * <p>
	 * To są jedyne dwa maile, które wyzwala ktoś spoza moderacji — a więc jedyne,
	 * którymi da się zasypać cudzą skrzynkę. Limit zgłoszeń per osoba tego nie
	 * domyka, bo chroni przed jedną osobą, a nie przed dziesięcioma umówionymi:
	 * ofiarą jest jedna skrzynka niezależnie od tego, ilu nadawców ją zapełnia (ta
	 * sama logika co {@code resendInterval} przy resecie hasła, Claude.md §9).
	 *
	 * <p>
	 * Wyciszony mail <b>przepada</b>, nie jest kolejkowany — i to jest w porządku
	 * dokładnie dlatego, że ten mail nigdy nie niósł treści: mówi „jest po co
	 * zajrzeć", a kolejka i tak pokazuje wszystko, co przyszło w międzyczasie.
	 * Powiadomienia w aplikacji zostają nietknięte.
	 *
	 * <p>
	 * <b>Fail-open</b> jak rate limiter (§9): gdy Redis nie odpowiada, mail
	 * wychodzi. Wyciszanie jest zabezpieczeniem, nie warunkiem poprawności — awaria
	 * cache'a nie może uciszyć kolejki moderacji.
	 */
	private void sendToModerators(UUID communityId, String kind, UUID actorId, String subject, String body) {
		if (!claimModeratorMailSlot(communityId, kind)) {
			// Wyciszenie jest z zewnątrz niewidoczne — moderator nie wie, że mail nie
			// przyszedł, bo wyglądałoby to tak samo jak brak spraw. Stąd licznik: stała
			// seria to znak, że odstęp jest za długi albo że ktoś uprawia zasypywanie.
			meterRegistry.counter(MAIL_SUPPRESSED_METRIC, "kind", kind).increment();
			log.debug("Mail do moderatorów społeczności {} ({}) wyciszony odstępem", communityId, kind);
			return;
		}
		// Zawieszony moderator nie może rozpatrzyć sprawy, do której ten mail wzywa
		// (ADR 0022) — wezwania do kolejki dostają tylko ci, którzy mogą ją otworzyć.
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

	/**
	 * Wysyłka do wielu adresatów naraz; sprawca decyzji odpada — nikt nie
	 * zawiadamia siebie, ta sama reguła co w {@code NotificationService.record}.
	 * Adresy pobieramy jednym zapytaniem; userId bez konta (usunięte w
	 * międzyczasie) po prostu nie ma w wyniku i cicho wypada.
	 *
	 * <p>
	 * Błąd wysyłki <b>nie</b> leci dalej i jest łapany per adresat — jeden
	 * niedoręczalny adres nie może zatrzymać maili do pozostałych moderatorów.
	 * Powiadomienie w skrzynce już istnieje (osobny listener), więc przepadnięcie
	 * maila degraduje kanał, nie informację. W logu wyłącznie userId, nigdy adres
	 * (Claude.md §13).
	 */
	private void sendTo(Set<UUID> recipientIds, UUID actorId, MailCategory category, String subject, String body) {
		Set<UUID> recipients = recipientIds.stream().filter(id -> !id.equals(actorId)).collect(Collectors.toSet());
		// Preferencje (ADR 0020) odsiewamy PER ADRESAT, nie „wyślij albo nie":
		// jeden moderator z wyłączoną pocztą nie może wyciszyć maila pozostałym.
		// Odstęp z ADR 0018 działa niezależnie i wcześniej — to okno należy do
		// społeczności, nie do adresata.
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
