package com.songly.profile;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Publiczna granica modułu profile dla innych modułów (Claude.md §2): odczyt
 * nazw wyświetlanych bez dostępu do encji {@code Profile}.
 */
public interface ProfileDirectory {

	/** Brakujące userId po prostu nie występują w wyniku. */
	Map<UUID, String> displayNames(Collection<UUID> userIds);
}
