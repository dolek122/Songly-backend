package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
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

	private ModerationNotificationMailer mailer;

	private final UUID communityId = UUID.randomUUID();
	private final UUID reporterId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		mailer = new ModerationNotificationMailer(mailSender, userEmailDirectory, communityDirectory,
				communityMembership);
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
		UUID moderator = UUID.randomUUID();
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.of("Metalowcy"));
		when(communityMembership.moderatorIds(communityId)).thenReturn(Set.of(moderator));
		when(userEmailDirectory.emailsOf(Set.of(moderator))).thenReturn(Map.of());

		assertThatCode(() -> mailer.onPostReported(reportedEvent())).doesNotThrowAnyException();

		verify(mailSender, never()).send(anyString(), anyString(), anyString());
	}

	/** Usunięta społeczność nie wywraca wysyłki — treść dostaje tekst zastępczy. */
	@Test
	void deletedCommunityDoesNotBreakTheMail() {
		UUID moderator = UUID.randomUUID();
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.empty());
		when(communityMembership.moderatorIds(communityId)).thenReturn(Set.of(moderator));
		when(userEmailDirectory.emailsOf(Set.of(moderator))).thenReturn(Map.of(moderator, "mod@songly.io"));

		assertThatCode(() -> mailer.onPostReported(reportedEvent())).doesNotThrowAnyException();

		verify(mailSender).send(eq("mod@songly.io"), any(), any());
	}
}
