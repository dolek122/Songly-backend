package com.songly.content.dto;

import com.songly.content.ReportReason;
import java.time.Instant;
import java.util.UUID;

public record PostReportResponse(UUID id, ReportReason reason, String details, UUID reporterId,
		String reporterDisplayName, Instant createdAt, long reportCount, int reporterUpheldCount,
		int reporterDismissedCount, PostResponse post) {
}
