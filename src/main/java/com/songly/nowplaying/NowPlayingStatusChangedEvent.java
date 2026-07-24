package com.songly.nowplaying;

import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import java.util.UUID;

/**
 * Zmiana statusu "co teraz gra" użytkownika {@code userId}. Publikowane
 * wewnątrz modułu (ręczny status, docelowo też serwerowy poll — ADR 0007),
 * konsumowane przez {@link NowPlayingStreamBroadcaster}, który pushuje je przez
 * SSE do połączonych znajomych.
 */
record NowPlayingStatusChangedEvent(UUID userId, NowPlayingStatusResponse status) {
}
