package com.songly.analytics;

import com.songly.analytics.dto.AnalyticsCountResponse;
import com.songly.analytics.dto.AnalyticsSummaryResponse;
import io.micrometer.core.instrument.MeterRegistry;
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

	/**
	 * Nazwa metryki Micrometera; w Prometheusie:
	 * {@code songly_domain_events_total{type="..."}}.
	 */
	private static final String DOMAIN_EVENTS_METRIC = "songly.domain.events";

	private final AnalyticsEventRepository repository;
	private final MeterRegistry meterRegistry;

	AnalyticsService(AnalyticsEventRepository repository, MeterRegistry meterRegistry) {
		this.repository = repository;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * REQUIRES_NEW celowo: wywoływane z {@code AFTER_COMMIT} listenerów, gdzie
	 * transakcja źródłowa już się zakończyła (ten sam wzorzec co
	 * {@code NotificationService.record}).
	 *
	 * <p>
	 * To samo zdarzenie zasila dwa niezależne odbiory: trwały licznik w bazie (API
	 * analityki produktowej, {@link #summary}) oraz metrykę Micrometera dla
	 * observability (scrape Prometheusa, Claude.md §13). Inkrementacja licznika po
	 * zapisie — gdy zapis rzuci, metryka nie zliczy zdarzenia, którego nie ma w
	 * bazie.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void record(AnalyticsEventType type, Instant occurredAt) {
		repository.save(new AnalyticsEvent(UUID.randomUUID(), type, occurredAt));
		meterRegistry.counter(DOMAIN_EVENTS_METRIC, "type", type.name()).increment();
	}

	@Transactional(readOnly = true)
	AnalyticsSummaryResponse summary(int windowDays) {
		int days = Math.max(1, Math.min(windowDays, MAX_WINDOW_DAYS));
		Instant since = Instant.now().minus(Duration.ofDays(days));
		Map<AnalyticsEventType, Long> counts = repository.countByTypeSince(since).stream().collect(Collectors
				.toMap(AnalyticsEventRepository.TypeCount::getType, AnalyticsEventRepository.TypeCount::getCount));
		return new AnalyticsSummaryResponse(days, since, Arrays.stream(AnalyticsEventType.values())
				.map(type -> new AnalyticsCountResponse(type, counts.getOrDefault(type, 0L))).toList());
	}
}
