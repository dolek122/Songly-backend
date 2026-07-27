package com.songly.identity;

/**
 * Ktoś podał refresh token, który został już zużyty (rotacja odwołała go przy
 * poprzednim odświeżeniu). Token powinien istnieć tylko w jednym egzemplarzu i
 * być użyty dokładnie raz, więc drugie użycie znaczy, że kopia jest w rękach
 * kogoś jeszcze.
 *
 * <p>
 * Dziedziczy po {@link InvalidRefreshTokenException} <b>celowo</b>: klient
 * dostaje dokładnie tę samą odpowiedź (401, {@code INVALID_REFRESH_TOKEN}) co
 * przy zwykłym złym tokenie. Osobna odpowiedź podpowiadałaby atakującemu, że
 * jego kopia została wykryta, i że warto zdążyć przed prawowitym użytkownikiem.
 * Osobny typ istnieje dla nas — po stronie serwera decyduje o rewokacji, logu i
 * o {@code noRollbackFor}.
 */
class RefreshTokenReuseDetectedException extends InvalidRefreshTokenException {
}
