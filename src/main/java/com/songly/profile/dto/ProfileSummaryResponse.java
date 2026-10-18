package com.songly.profile.dto;

import java.util.UUID;

/**
 * Nazwa i awatar użytkownika dla administracji (ADR 0036). Bez bio i gatunków:
 * wykazy administracyjne niosą same identyfikatory, a to jest dokładnie tyle,
 * ile trzeba, żeby wiedzieć, o kim mowa.
 *
 * <p>
 * {@code suspended} mówi, że zwykły użytkownik tego profilu nie widzi (ADR
 * 0026) — administrator widzi go mimo to, ale powinien wiedzieć, że ogląda coś
 * ukrytego.
 */
public record ProfileSummaryResponse(UUID userId, String displayName, String avatarUrl, boolean suspended) {
}
