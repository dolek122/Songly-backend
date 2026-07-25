package com.songly.nowplaying;

import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import java.util.UUID;

/**
 * Zmiana statusu "co teraz gra" użytkownika {@code userId}, rozsyłana kanałem
 * Redis Pub/Sub do <b>wszystkich</b> instancji aplikacji (ADR 0007, Etap 2).
 * Każda instancja pushuje ją przez SSE tym swoim lokalnym subskrybentom, którzy
 * są znajomymi {@code userId} — dzięki temu zmiana zarejestrowana na instancji
 * A dociera do klienta trzymanego przez instancję B.
 *
 * <p>
 * To kontrakt <b>między instancjami</b>, nie tylko wewnątrz procesu: zmiana
 * kształtu tego rekordu musi przetrwać rolling deploy, w którym stare i nowe
 * instancje słuchają tego samego kanału (dokładanie pól jest bezpieczne,
 * usuwanie i zmiana znaczenia — nie).
 */
record NowPlayingStatusChangedMessage(UUID userId, NowPlayingStatusResponse status) {
}
