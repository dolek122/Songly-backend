package com.songly.identity;

import java.util.Set;
import java.util.UUID;

/**
 * Publiczna granica modułu identity dla pytania „kto jest administratorem"
 * (Claude.md §2).
 *
 * <p>
 * Powstało dla modułu notification (ADR 0023), który musi zawiadomić
 * administratorów o nowym odwołaniu od zawieszenia, ale nie ma prawa czytać
 * tabeli {@code users}. Zwracamy identyfikatory, nie encje ani DTO — konsument
 * potrzebuje wyłącznie adresatów, dokładnie jak przy
 * {@code CommunityMembership.moderatorIds}.
 */
public interface AdminDirectory {

	Set<UUID> adminIds();
}
