package com.songly.nowplaying.dto;

import java.util.UUID;

/**
 * Status "co teraz gra" jednego znajomego, wzbogacony o jego dane wyświetlane
 * (jak pozostałe DTO odczytowe — nazwa + avatar), żeby klient nie musiał
 * dołączać profilu osobno. Zwracany tylko dla znajomych, którzy mają aktualny
 * status w cache'u — reszta jest pomijana.
 */
public record FriendNowPlayingResponse(UUID userId, String displayName, String avatarUrl,
		NowPlayingStatusResponse status) {
}
