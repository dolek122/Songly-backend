package com.songly.analytics.dto;

import com.songly.analytics.AnalyticsEventType;

public record AnalyticsCountResponse(AnalyticsEventType type, long count) {
}
