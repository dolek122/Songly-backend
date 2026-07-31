package com.songly.identity;

import java.util.UUID;

/**
 * Ktoś próbował założyć konto na adres, pod którym konto już istnieje.
 *
 * <p>
 * To zdarzenie jest ceną za zamknięcie wyroczni: skoro rejestracja nie może
 * odpowiedzieć „ten adres jest zajęty", nie wolno jej też po prostu zamilknąć.
 * Wiadomość idzie do <b>właściciela konta</b>, nie do zgłaszającego — czyli do
 * jedynej osoby, która ma prawo się o tym dowiedzieć, i to ona decyduje, czy to
 * była jej własna pomyłka, czy cudza próba.
 */
record RegistrationAttemptedOnExistingAccountEvent(UUID userId, String email) {
}
