package com.songly.analytics;

import com.songly.analytics.dto.AnalyticsCountResponse;
import com.songly.analytics.dto.AnalyticsSummaryResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AnalyticsService {

	private static final int MAX_WINDOW_DAYS = 365;

	private final AnalyticsEventRepository repository;

	AnalyticsService(AnalyticsEventRepository repository) {
		this.repository = repository;
	}

	/**
	 * REQUIRES_NEW celowo: wywoływane z {@code AFTER_COMMIT} listenerów, gdzie
	 * transakcja źródłowa już się zakończyła (ten sam wzorzec co
	 * {@code NotificationService.record}).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void record(AnalyticsEventType type, Instant occurredAt) {
		repository.save(new AnalyticsEvent(UUID.randomUUID(), type, occurredAt));
	}

	@Transactional(readOnly = true)
	AnalyticsSummaryResponse summary(int windowDays) {
		int days = Math.max(1, Math.min(windowDays, MAX_WINDOW_DAYS));
		Instant since = Instant.now().minus(Duration.ofDays(days));
		Map<AnalyticsEventType, Long> counts = repository.countByTypeSince(since).stream().collect(
				Collectors.toMap(AnalyticsEventRepository.TypeCount::getType, AnalyticsEventRepository.TypeCount::getCount));
		return new AnalyticsSummaryResponse(days, since, Arrays.stream(AnalyticsEventType.values())
				.map(type -> new AnalyticsCountResponse(type, counts.getOrDefault(type, 0L))).toList());
	}
}
