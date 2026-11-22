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
import com.songly.identity.AdminDirectory;
import com.songly.identity.SuspendedAccounts;
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

	@Mock
	private AdminDirectory adminDirectory;

	@Mock
	private SuspendedAccounts suspendedAccounts;

	private ModerationNotificationMailer mailer;

	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final UUID communityId = UUID.randomUUID();
	private final UUID reporterId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		mailer = new ModerationNotificationMailer(mailSender, userEmailDirectory, communityDirectory,
				communityMembership, redisTemplate, new ModerationMailProperties(), meterRegistry, mailPreferences,
				adminDirectory, suspendedAccounts);
	}

	private void mailSlotFree() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
	}

	private void allRecipientsAllowed() {
		when(mailPreferences.allowedRecipients(anySet(), any(MailCategory.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	private PostReportedEvent reportedEvent() {
		return new PostReportedEvent(communityId, UUID.randomUUID(), reporterId, Instant.now());
	}

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

	@Test
	void suspendedModeratorIsLeftOutOfQueueMail() {
		mailSlotFree();
		allRecipientsAllowed();
		UUID suspendedModerator = UUID.randomUUID();
		UUID activeModerator = UUID.randomUUID();
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.of("Metalowcy"));
		when(communityMembership.moderatorIds(communityId)).thenReturn(Set.of(suspendedModerator, activeModerator));
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of(suspendedModerator));
		when(userEmailDirectory.emailsOf(Set.of(activeModerator))).thenReturn(Map.of(activeModerator, "mod@songly.io"));

		mailer.onPostReported(reportedEvent());

		verify(userEmailDirectory).emailsOf(Set.of(activeModerator));
		verify(mailSender).send(eq("mod@songly.io"), anyString(), anyString());
	}

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

	@Test
	void secondModeratorMailWithinTheIntervalIsSuppressed() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
		when(communityDirectory.nameOf(communityId)).thenReturn(Optional.of("Metalowcy"));

		mailer.onPostReported(reportedEvent());

		verify(communityMembership, never()).moderatorIds(any());
		verify(mailSender, never()).send(anyString(), anyString(), anyString());
		assertThat(meterRegistry.counter("songly.moderation.mail.suppressed", "kind", "report").count()).isEqualTo(1);
	}

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
