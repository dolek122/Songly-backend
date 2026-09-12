package com.songly.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Zawieszony złożył odwołanie (ADR 0023). Adresatami powiadomienia są wszyscy
 * administratorzy — jak przy zgłoszeniu posta i odwołaniu od banu (ADR 0012,
 * 0017): wykaz zawieszeń bez powiadomienia to narzędzie, którego nic nie
 * uruchamia, a odwołanie przychodzi od użytkownika, więc nikt inny o nim nie
 * wie.
 *
 * <p>
 * Publikowane co najwyżej raz na zawieszenie, bo odwołanie jest jedno —
 * ograniczenie wolumenu wychodzi tu ze struktury danych, nie z odstępu
 * czasowego (inaczej niż przy kolejce zgłoszeń, ADR 0018).
 */
public record UserSuspensionAppealSubmittedEvent(UUID userId, Instant occurredAt) {
}
