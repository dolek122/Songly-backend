package com.songly.identity;

import java.util.UUID;

/**
 * Zdarzenie <b>wewnętrzne</b> modułu identity (stąd brak {@code public}) —
 * pomost między zapisem tokenu a wysyłką maila.
 *
 * <p>
 * Istnieje po to, żeby wysyłka odbyła się dopiero {@code AFTER_COMMIT}. Mail
 * wysłany wewnątrz transakcji, która potem się wycofa, niesie link do tokenu,
 * którego w bazie nigdy nie było — użytkownik dostaje wiadomość i błąd przy
 * kliknięciu, bez żadnego śladu, dlaczego. Przy okazji rozmowa z serwerem SMTP
 * nie trzyma otwartej transakcji bazodanowej.
 *
 * @param token
 *            token w postaci jawnej — jedyny moment, w którym istnieje poza
 *            odpowiedzią do klienta. Nie wolno go logować.
 */
record PasswordResetRequestedEvent(UUID userId, String email, String token) {
}
