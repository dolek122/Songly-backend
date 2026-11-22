package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionCleanupJobTest {

	@Mock
	private SessionRepository sessionRepository;

	private final SessionCleanupProperties properties = new SessionCleanupProperties();
	private SessionCleanupJob job;

	@BeforeEach
	void setUp() {
		properties.setBatchSize(100);
		properties.setMaxBatchesPerRun(3);
		job = new SessionCleanupJob(sessionRepository, properties);
	}

	@Test
	void stopsAfterFirstIncompleteBatch() {
		when(sessionRepository.deleteDeadSessions(any(), anyInt())).thenReturn(100, 42);

		job.removeDeadSessions();

		verify(sessionRepository, times(2)).deleteDeadSessions(any(), anyInt());
	}

	@Test
	void neverExceedsBatchLimitInOneRun() {
		when(sessionRepository.deleteDeadSessions(any(), anyInt())).thenReturn(100);

		job.removeDeadSessions();

		verify(sessionRepository, times(3)).deleteDeadSessions(any(), anyInt());
	}

	@Test
	void asksForSessionsDeadLongerThanRetention() {
		properties.setRetention(Duration.ofDays(7));
		when(sessionRepository.deleteDeadSessions(any(), anyInt())).thenReturn(0);
		Instant before = Instant.now();

		job.removeDeadSessions();

		ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
		verify(sessionRepository).deleteDeadSessions(cutoff.capture(), anyInt());
		assertThat(cutoff.getValue()).isBetween(before.minus(Duration.ofDays(7)),
				Instant.now().minus(Duration.ofDays(7)));
	}

	@Test
	void survivesDatabaseFailure() {
		when(sessionRepository.deleteDeadSessions(any(), anyInt())).thenThrow(new IllegalStateException("baza padła"));

		assertThatCode(() -> job.removeDeadSessions()).doesNotThrowAnyException();
	}
}
