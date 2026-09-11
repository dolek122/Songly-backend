package com.songly.community;

import java.util.Optional;
import java.util.UUID;

/**
 * Publiczna granica modułu community dla odczytu danych opisowych społeczności
 * (Claude.md §2) — w odróżnieniu od {@link CommunityMembership}, który
 * odpowiada na pytania o członkostwo.
 *
 * <p>
 * Powstało dla modułu notification (ADR 0013): wiadomość e-mail o decyzji
 * moderacyjnej musi nazwać społeczność po imieniu, a zdarzenia domenowe niosą
 * wyłącznie jej identyfikator.
 */
public interface CommunityDirectory {

	/** Pusty wynik, gdy społeczność nie istnieje (np. usunięta w międzyczasie). */
	Optional<String> nameOf(UUID communityId);
}
