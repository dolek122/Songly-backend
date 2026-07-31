package com.songly.content.dto;

import com.songly.content.ReportReason;
import java.time.Instant;
import java.util.UUID;

/**
 * Pozycja w kolejce moderacji: zgłoszenie razem z postem, którego dotyczy —
 * moderator nie powinien musieć dopytywać o treść osobnym żądaniem.
 *
 * <p>
 * {@code reportCount} to liczba niezależnych osób, które zgłosiły ten post.
 * Ważniejsza niż samo zgłoszenie, bo jedno zgłoszenie bywa złośliwe, a
 * kilkanaście raczej nie.
 */
public record PostReportResponse(UUID id, ReportReason reason, String details, UUID reporterId,
		String reporterDisplayName, Instant createdAt, long reportCount, PostResponse post) {
}
