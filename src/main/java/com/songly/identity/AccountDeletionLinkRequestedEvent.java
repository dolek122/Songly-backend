package com.songly.identity;

import java.util.UUID;

/**
 * Zdarzenie <b>wewnętrzne</b> modułu identity — pomost między zapisem tokenu a
 * wysyłką maila {@code AFTER_COMMIT}, jak {@link PasswordResetRequestedEvent}.
 *
 * <p>
 * Nie mylić z {@link UserAccountDeletionRequestedEvent}: tamto zdarzenie każe
 * innym modułom kasować dane, a to tylko wysyła link, po którym użytkownik
 * dopiero podejmie decyzję.
 *
 * @param token
 *            token w postaci jawnej — nie wolno go logować.
 */
record AccountDeletionLinkRequestedEvent(UUID userId, String email, String token) {
}
