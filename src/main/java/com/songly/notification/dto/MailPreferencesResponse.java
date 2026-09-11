package com.songly.notification.dto;

/**
 * Preferencje kanału e-mail (ADR 0020). Skrzynki w aplikacji nie ma tu celowo —
 * jest kompletna zawsze i nie podlega ustawieniom.
 */
public record MailPreferencesResponse(boolean decisionsAboutMe, boolean moderationQueue) {
}
