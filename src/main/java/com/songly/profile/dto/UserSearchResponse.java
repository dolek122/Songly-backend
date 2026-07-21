package com.songly.profile.dto;

import java.util.UUID;

/**
 * Wynik wyszukiwania użytkownika — minimum potrzebne, żeby wysłać zaproszenie
 * do znajomych ({@code addresseeId}) i pokazać go na liście. Bez bio i danych
 * wrażliwych: wyszukiwarka jest dostępna dla każdego zalogowanego użytkownika.
 */
public record UserSearchResponse(UUID userId, String displayName, String avatarUrl) {
}
