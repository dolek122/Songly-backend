package com.songly.notification;

import com.songly.common.push.InvalidPushTokenException;
import com.songly.common.push.PushMessage;
import com.songly.common.push.PushSender;
import com.songly.community.CommunityDirectory;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Trzeci kanał powiadomień (ADR 0040): push na telefon obok skrzynki i maila.
 *
 * <p>
 * <b>Wychodzą wszystkie typy, ale filtr blokad działa w chwili wysyłki.</b>
 * Skrzynka ukrywa zablokowanych przy odczycie (Claude.md §9), maila
 * ograniczyliśmy do moderacji, bo nie da się go ukryć po fakcie (ADR 0013).
 * Push też nie, ale push wyłącznie o moderacji byłby funkcją martwą dla prawie
 * wszystkich. Kompromis: blokada sprzed zdarzenia wycisza push, a push wysłany
 * przed blokadą zostaje na telefonie — to ten sam stan, co powiadomienie
 * przeczytane przed blokadą. Typy {@code MODERATION} przechodzą mimo blokady,
 * tak jak w skrzynce (ADR 0012).
 *
 * <p>
 * <b>Treść: typ i nazwa aktora, nic więcej.</b> Bez tekstu komentarza, bez
 * powodów sankcji i bez nazwisk przy decyzjach moderacyjnych — tam zamiast
 * aktora stoi społeczność, bo ban należy do społeczności, a nie do moderatora
 * (Claude.md §8). Ekran blokady telefonu to kanał push w najczystszej postaci.
 *
 * <p>
 * Wysyłka synchronicznie, po commicie zapisu do skrzynki, w wątku żądania,
 * które je wywołało — jak mail. Awaria jednego urządzenia nie zatrzymuje
 * pozostałych, a żadna nie wraca do wywołującego: skrzynka już zapisała.
 */
@Component
class PushNotifier {

	private static final Logger log = LoggerFactory.getLogger(PushNotifier.class);

	/** W Prometheusie: {@code songly_push_deliveries_total{outcome}}. */
	private static final String DELIVERIES_METRIC = "songly.push.deliveries";

	private static final String APP_NAME = "Songly";

	private final PushSender pushSender;
	private final PushDeviceService devices;
	private final SocialGraph socialGraph;
	private final ProfileDirectory profileDirectory;
	private final CommunityDirectory communityDirectory;
	private final MeterRegistry meterRegistry;

	PushNotifier(PushSender pushSender, PushDeviceService devices, SocialGraph socialGraph,
			ProfileDirectory profileDirectory, CommunityDirectory communityDirectory, MeterRegistry meterRegistry) {
		this.pushSender = pushSender;
		this.devices = devices;
		this.socialGraph = socialGraph;
		this.profileDirectory = profileDirectory;
		this.communityDirectory = communityDirectory;
		this.meterRegistry = meterRegistry;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onNotificationCreated(NotificationCreatedEvent event) {
		List<String> tokens = devices.tokensOf(event.recipientId());
		if (tokens.isEmpty()) {
			return;
		}
		if (!NotificationType.MODERATION.contains(event.type())
				&& socialGraph.blockedEitherWayIds(event.recipientId()).contains(event.actorId())) {
			outcome("skipped-blocked");
			return;
		}
		Text text = textFor(event);
		Map<String, String> data = Map.of("type", event.type().name(), "notificationId",
				event.notificationId().toString(), "resourceId", event.resourceId().toString(), "title", text.title(),
				"body", text.body());
		for (String token : tokens) {
			deliver(event.recipientId(), new PushMessage(token, text.title(), text.body(), data));
		}
	}

	private void deliver(UUID recipientId, PushMessage message) {
		try {
			pushSender.send(message);
			outcome("sent");
		} catch (InvalidPushTokenException ex) {
			// Telefonu już nie ma — bez tego każdy kolejny push trafiałby w próżnię.
			devices.forget(message.token());
			outcome("invalid-token");
		} catch (RuntimeException ex) {
			outcome("failed");
			log.warn("Nie udało się wysłać pusha do użytkownika {}: {}", recipientId, ex.getMessage());
		}
	}

	private void outcome(String outcome) {
		meterRegistry.counter(DELIVERIES_METRIC, "outcome", outcome).increment();
	}

	private record Text(String title, String body) {
	}

	/**
	 * {@code switch} bez {@code default} celowo: nowy typ powiadomienia nie
	 * skompiluje się, dopóki ktoś nie zdecyduje, co mówi jego push — także o tym,
	 * czy niesie nazwę aktora.
	 */
	private Text textFor(NotificationCreatedEvent event) {
		return switch (event.type()) {
			case POST_LIKED -> new Text(actor(event), "Polubienie Twojego posta");
			case POST_COMMENTED -> new Text(actor(event), "Nowy komentarz pod Twoim postem");
			case POST_SHARED -> new Text(actor(event), "Udostępnienie Twojego posta");
			case FRIEND_REQUEST_RECEIVED -> new Text(actor(event), "Zaproszenie do znajomych");
			case FRIEND_REQUEST_ACCEPTED -> new Text(actor(event), "Zaproszenie przyjęte — jesteście znajomymi");
			case COMMUNITY_MEMBER_JOINED -> new Text(actor(event), "Dołączenie do " + community(event));
			case COMMUNITY_MODERATOR_GRANTED -> new Text(community(event), "Masz teraz uprawnienia moderatora");
			case COMMUNITY_MODERATOR_REVOKED -> new Text(community(event), "Nie masz już uprawnień moderatora");
			case COMMUNITY_BANNED -> new Text(community(event), "Moderacja zablokowała Ci pisanie w tej społeczności");
			case COMMUNITY_POST_REMOVED -> new Text(community(event), "Moderacja usunęła Twój post");
			// Zasobem jest post, nie społeczność — tytuł bez nazwy, ale też bez moderatora.
			case COMMUNITY_COMMENT_REMOVED -> new Text(APP_NAME, "Moderacja usunęła Twój komentarz");
			case BAN_APPEAL_SUBMITTED -> new Text(community(event), "Nowe odwołanie od bana do rozpatrzenia");
			case BAN_APPEAL_ACCEPTED -> new Text(community(event), "Odwołanie uznane — ban zdjęty");
			case BAN_APPEAL_REJECTED -> new Text(community(event), "Odwołanie od bana odrzucone");
			case BAN_ESCALATION_ACCEPTED -> new Text(community(event), "Administracja uchyliła ban");
			case BAN_ESCALATION_REJECTED -> new Text(community(event), "Administracja utrzymała ban");
			// Tu zasobem jest post, nie społeczność — więc bez nazwy, za to bez wskazywania
			// zgłaszającego.
			case COMMUNITY_POST_REPORTED -> new Text(APP_NAME, "Nowe zgłoszenie posta do rozpatrzenia");
			case POST_REPORT_DISMISSED -> new Text(APP_NAME, "Moderacja nie uwzględniła Twojego zgłoszenia");
		};
	}

	private String actor(NotificationCreatedEvent event) {
		String name = profileDirectory.displayNames(Set.of(event.actorId())).get(event.actorId());
		return name != null ? name : APP_NAME;
	}

	private String community(NotificationCreatedEvent event) {
		return communityDirectory.nameOf(event.resourceId()).map("„%s”"::formatted).orElse(APP_NAME);
	}
}
