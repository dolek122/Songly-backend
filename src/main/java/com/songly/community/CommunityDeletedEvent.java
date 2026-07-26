package com.songly.community;

import java.util.UUID;

/**
 * Społeczność jest usuwana. Moduły trzymające dane przypięte do społeczności
 * muszą je posprzątać.
 *
 * <p>
 * <b>Uwaga na tę samą pułapkę, co przy usuwaniu konta (Claude.md §9):</b>
 * słuchacze tego zdarzenia muszą używać zwykłego {@code @EventListener}, a nie
 * {@code @TransactionalEventListener(AFTER_COMMIT)}. Zdarzenie leci
 * <b>przed</b> usunięciem wiersza {@code communities}, a {@code posts} ma do
 * niego klucz obcy — sprzątanie musi się wykonać synchronicznie, w tej samej
 * transakcji, inaczej baza odrzuci {@code DELETE}.
 */
public record CommunityDeletedEvent(UUID communityId) {
}
