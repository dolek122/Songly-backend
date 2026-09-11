package com.songly.notification;

import com.songly.common.mail.MailSender;
import com.songly.community.CommunityBanAppealAcceptedEvent;
import com.songly.community.CommunityBanAppealRejectedEvent;
import com.songly.community.CommunityBanAppealSubmittedEvent;
import com.songly.community.CommunityDirectory;
import com.songly.community.CommunityMemberBannedEvent;
import com.songly.community.CommunityMembership;
import com.songly.content.CommunityPostRemovedEvent;
import com.songly.content.PostReportDismissedEvent;
import com.songly.content.PostReportedEvent;
import com.songly.identity.UserEmailDirectory;
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

	private final MailSender mailSender;
	private final UserEmailDirectory userEmailDirectory;
	private final CommunityDirectory communityDirectory;
	private final CommunityMembership communityMembership;
	private final StringRedisTemplate redisTemplate;
	private final ModerationMailProperties properties;

	ModerationNotificationMailer(MailSender mailSender, UserEmailDirectory userEmailDirectory,
			CommunityDirectory communityDirectory, CommunityMembership communityMembership,
			StringRedisTemplate redisTemplate, ModerationMailProperties properties) {
		this.mailSender = mailSender;
		this.userEmailDirectory = userEmailDirectory;
		this.communityDirectory = communityDirectory;
		this.communityMembership = communityMembership;
		this.redisTemplate = redisTemplate;
		this.properties = properties;
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
		sendTo(Set.of(event.userId()), event.moderatorId(), "Songly — ban w społeczności „%s”".formatted(community), """
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
	void onCommunityPostRemoved(CommunityPostRemovedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.postAuthorId()), event.moderatorId(),
				"Songly — Twój post w społeczności „%s” został usunięty".formatted(community), """
						Cześć!

						Moderacja społeczności „%s” w Songly usunęła Twój post.

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
		sendTo(Set.of(event.userId()), event.moderatorId(),
				"Songly — Twoje odwołanie w społeczności „%s” zostało uznane".formatted(community), """
						Cześć!

						Moderacja społeczności „%s” w Songly uznała Twoje odwołanie.
						Ban został zdjęty — możesz wrócić do społeczności.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onBanAppealRejected(CommunityBanAppealRejectedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.userId()), event.moderatorId(),
				"Songly — Twoje odwołanie w społeczności „%s” zostało odrzucone".formatted(community), """
						Cześć!

						Moderacja społeczności „%s” w Songly rozpatrzyła Twoje odwołanie
						i uznała, że ban pozostaje w mocy. Ta decyzja jest ostateczna dla
						tego bana.

						Zespół Songly""".formatted(community));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onPostReportDismissed(PostReportDismissedEvent event) {
		String community = communityName(event.communityId());
		sendTo(Set.of(event.reporterId()), event.moderatorId(), "Songly — Twoje zgłoszenie zostało rozpatrzone", """
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
			log.debug("Mail do moderatorów społeczności {} ({}) wyciszony odstępem", communityId, kind);
			return;
		}
		sendTo(communityMembership.moderatorIds(communityId), actorId, subject, body);
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
	private void sendTo(Set<UUID> recipientIds, UUID actorId, String subject, String body) {
		Set<UUID> recipients = recipientIds.stream().filter(id -> !id.equals(actorId)).collect(Collectors.toSet());
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
