package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.common.mail.MailDeliveryException;
import com.songly.common.mail.MailSender;
import com.songly.community.CommunityDirectory;
import com.songly.community.CommunityMembership;
import com.songly.content.PostReportedEvent;
import com.songly.identity.UserEmailDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Właściwości kanału e-mail, których test integracyjny nie wymusi wprost:
 * odporność na awarię wysyłki i ciche pomijanie adresatów bez konta.
 */
@ExtendWith(MockitoExtension.class)
class ModerationNotificationMailerTest {

	@Mock
	private MailSender mailSender;

	@Mock
	private UserEmailDirectory userEmailDirectory;

	@Mock
	private CommunityDirectory communityDirectory;

	@Mock
	private CommunityMembership communityMembership;

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private NotificationMailPreferenceService mailPreferences;

	private ModerationNotificationMailer mailer;

	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final UUID communityId = UUID.randomUUID();
	private final UUID reporterId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		mailer = new ModerationNotificationMailer(mailSender, userEmailDirectory, communityDirectory,
				communityMembership, redisTemplate, new ModerationMailProperties(), meterRegistry, mailPreferences);
	}

	/** Domyślnie okno wolne — wyciszanie sprawdzają osobne testy. */
	private void mailSlotFree() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
	}

	/**
	 * Nikt nie wypisał się z poczty — filtr preferencji (ADR 0020) ma osobne testy.
	 */
	private void allRecipientsAllowed() {
		when(mailPreferences.allowedRecipients(anySet(), any(MailCategory.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	private PostReportedEvent reportedEvent() {
		return new PostReportedEvent(communityId, UUID.randomUUID(), reporterId, Instant.now());
	}

	/**
	 * Jeden niedoręczalny adres nie może zatrzymać maili do pozostałych moderatorów
	 * — a błąd wysyłki nie ma prawa wylecieć z listenera.
	 */
	@Test
	void deliveryFailureForOneModeratorDoesNotStopTheOthers() {
		mailSlotFree();
		allRecipientsAllowed();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.of("Metalowcy"));
		when(communityMembership.moderatorIds(communityId)).thenReturn(Set.of(first, second));
		when(userEmailDirectory.emailsOf(Set.of(first, second)))
				.thenReturn(Map.of(first, "first@songly.io", second, "second@songly.io"));
		doThrow(new MailDeliveryException("SMTP down", null)).when(mailSender).send(eq("first@songly.io"), anyString(),
				anyString());

		assertThatCode(() -> mailer.onPostReported(reportedEvent())).doesNotThrowAnyException();

		verify(mailSender).send(eq("second@songly.io"), anyString(), anyString());
	}

	/**
	 * Zgłaszający będący moderatorem odpada <b>przed</b> pytaniem o adresy — ta
	 * sama reguła „nikt nie zawiadamia siebie" co w {@code NotificationService}.
	 */
	@Test
	void reportingModeratorIsFilteredOutBeforeEmailLookup() {
		mailSlotFree();
		allRecipientsAllowed();
		UUID otherModerator = UUID.randomUUID();
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.of("Metalowcy"));
		when(communityMembership.moderatorIds(communityId)).thenReturn(Set.of(reporterId, otherModerator));
		when(userEmailDirectory.emailsOf(Set.of(otherModerator))).thenReturn(Map.of(otherModerator, "mod@songly.io"));

		mailer.onPostReported(reportedEvent());

		verify(userEmailDirectory).emailsOf(Set.of(otherModerator));
		verify(mailSender).send(eq("mod@songly.io"), anyString(), anyString());
	}

	/**
	 * Konto usunięte między decyzją a wysyłką: brak adresu, brak maila, brak błędu.
	 */
	@Test
	void recipientWithoutAccountIsSkippedSilently() {
		mailSlotFree();
		allRecipientsAllowed();
		UUID moderator = UUID.randomUUID();
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.of("Metalowcy"));
		when(communityMembership.moderatorIds(communityId)).thenReturn(Set.of(moderator));
		when(userEmailDirectory.emailsOf(Set.of(moderator))).thenReturn(Map.of());

		assertThatCode(() -> mailer.onPostReported(reportedEvent())).doesNotThrowAnyException();

		verify(mailSender, never()).send(anyString(), anyString(), anyString());
	}

	/**
	 * Odstęp wyciszający (ADR 0018): drugi mail do moderatorów tej samej
	 * społeczności w oknie przepada, i to <b>przed</b> pytaniem o adresatów —
	 * wyciszamy wysyłkę, a nie doręczenie.
	 */
	@Test
	void secondModeratorMailWithinTheIntervalIsSuppressed() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.of("Metalowcy"));

		mailer.onPostReported(reportedEvent());

		verify(communityMembership, never()).moderatorIds(any());
		verify(mailSender, never()).send(anyString(), anyString(), anyString());
		// Wyciszenie musi być widoczne w metrykach — z zewnątrz wygląda identycznie
		// jak brak spraw (ADR 0019).
		assertThat(meterRegistry.counter("songly.moderation.mail.suppressed", "kind", "report").count()).isEqualTo(1);
	}

	/**
	 * Fail-open jak rate limiter (Claude.md §9): awaria Redisa nie może uciszyć
	 * kolejki moderacji, więc mail wychodzi.
	 */
	@Test
	void redisFailureLetsTheMailThrough() {
		allRecipientsAllowed();
		UUID moderator = UUID.randomUUID();
		when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.of("Metalowcy"));
		when(communityMembership.moderatorIds(communityId)).thenReturn(Set.of(moderator));
		when(userEmailDirectory.emailsOf(Set.of(moderator))).thenReturn(Map.of(moderator, "mod@songly.io"));

		assertThatCode(() -> mailer.onPostReported(reportedEvent())).doesNotThrowAnyException();

		verify(mailSender).send(eq("mod@songly.io"), anyString(), anyString());
	}

	/** Usunięta społeczność nie wywraca wysyłki — treść dostaje tekst zastępczy. */
	@Test
	void deletedCommunityDoesNotBreakTheMail() {
		mailSlotFree();
		allRecipientsAllowed();
		UUID moderator = UUID.randomUUID();
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.empty());
		when(communityMembership.moderatorIds(communityId)).thenReturn(Set.of(moderator));
		when(userEmailDirectory.emailsOf(Set.of(moderator))).thenReturn(Map.of(moderator, "mod@songly.io"));

		assertThatCode(() -> mailer.onPostReported(reportedEvent())).doesNotThrowAnyException();

		verify(mailSender).send(eq("mod@songly.io"), any(), any());
	}
}
