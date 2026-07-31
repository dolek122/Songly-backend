package com.songly.identity;

/**
 * Zdarzenie wewnętrzne modułu identity: wyślij link potwierdzający adres.
 *
 * <p>
 * Istnieje z tego samego powodu co odpowiednik przy resecie hasła — wysyłka ma
 * się odbyć {@code AFTER_COMMIT}. Wiadomość wysłana wewnątrz transakcji, która
 * potem się wycofa, niesie link do zgłoszenia, którego w bazie nigdy nie było.
 *
 * @param token
 *            token w postaci jawnej — jedyny moment, w którym istnieje poza
 *            wysłaną wiadomością. Nie wolno go logować.
 */
record RegistrationVerificationRequestedEvent(String email, String token) {
}
