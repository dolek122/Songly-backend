package com.songly.analytics.dto;

import java.time.Instant;
import java.util.List;

public record AnalyticsSummaryResponse(int windowDays, Instant since, List<AnalyticsCountResponse> counts) {
}
