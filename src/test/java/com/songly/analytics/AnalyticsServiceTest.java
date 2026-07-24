package com.songly.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.analytics.dto.AnalyticsSummaryResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

	@Mock
	private AnalyticsEventRepository repository;

	private MeterRegistry meterRegistry;

	private AnalyticsService analyticsService;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		analyticsService = new AnalyticsService(repository, meterRegistry);
	}

	@Test
	void record_savesEvent() {
		analyticsService.record(AnalyticsEventType.POST_LIKED, Instant.now());

		verify(repository).save(any(AnalyticsEvent.class));
	}

	@Test
	void record_incrementsDomainEventCounterTaggedByType() {
		analyticsService.record(AnalyticsEventType.POST_LIKED, Instant.now());
		analyticsService.record(AnalyticsEventType.POST_LIKED, Instant.now());
		analyticsService.record(AnalyticsEventType.FRIEND_REQUEST_SENT, Instant.now());

		assertThat(meterRegistry.counter("songly.domain.events", "type", "POST_LIKED").count()).isEqualTo(2.0);
		assertThat(meterRegistry.counter("songly.domain.events", "type", "FRIEND_REQUEST_SENT").count()).isEqualTo(1.0);
	}

	@Test
	void summary_defaultsMissingTypesToZero() {
		when(repository.countByTypeSince(any())).thenReturn(List.of(countOf(AnalyticsEventType.POST_LIKED, 3)));

		AnalyticsSummaryResponse response = analyticsService.summary(7);

		assertThat(response.windowDays()).isEqualTo(7);
		assertThat(response.counts()).hasSize(AnalyticsEventType.values().length);
		assertThat(response.counts()).filteredOn(c -> c.type() == AnalyticsEventType.POST_LIKED)
				.extracting(com.songly.analytics.dto.AnalyticsCountResponse::count).containsExactly(3L);
		assertThat(response.counts()).filteredOn(c -> c.type() == AnalyticsEventType.POST_SHARED)
				.extracting(com.songly.analytics.dto.AnalyticsCountResponse::count).containsExactly(0L);
	}

	@Test
	void summary_clampsWindowToValidRange() {
		when(repository.countByTypeSince(any())).thenReturn(List.of());

		assertThat(analyticsService.summary(0).windowDays()).isEqualTo(1);
		assertThat(analyticsService.summary(-5).windowDays()).isEqualTo(1);
		assertThat(analyticsService.summary(10_000).windowDays()).isEqualTo(365);
	}

	private AnalyticsEventRepository.TypeCount countOf(AnalyticsEventType type, long count) {
		return new AnalyticsEventRepository.TypeCount() {

			@Override
			public AnalyticsEventType getType() {
				return type;
			}

			@Override
			public long getCount() {
				return count;
			}
		};
	}
}
