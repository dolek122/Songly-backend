package com.songly.social;

import java.util.Set;
import java.util.UUID;

/**
 * Publiczna granica modułu social dla innych modułów (Claude.md §2): odczyt
 * relacji bez dostępu do encji {@code Friendship}/{@code Block}. Z tego będzie
 * korzystał feed (posty znajomych) i matching.
 */
public interface SocialGraph {

	Set<UUID> friendIds(UUID userId);

	/** Blokada w którymkolwiek kierunku ukrywa użytkowników przed sobą nawzajem. */
	boolean isBlockedEitherWay(UUID userId, UUID otherUserId);

	/**
	 * Wszyscy użytkownicy powiązani z {@code userId} blokadą w dowolnym kierunku.
	 */
	Set<UUID> blockedEitherWayIds(UUID userId);
}
