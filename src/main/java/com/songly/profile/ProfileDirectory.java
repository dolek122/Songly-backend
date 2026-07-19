package com.songly.profile;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Publiczna granica modułu profile dla innych modułów (Claude.md §2): odczyt
 * nazw wyświetlanych i ulubionych gatunków bez dostępu do encji modułu.
 */
public interface ProfileDirectory {

	/** Brakujące userId po prostu nie występują w wyniku. */
	Map<UUID, String> displayNames(Collection<UUID> userIds);

	/** Użytkownicy mający wśród ulubionych którykolwiek z podanych gatunków. */
	Set<UUID> userIdsWithAnyGenre(Collection<UUID> genreIds);

	/** Pełne zbiory ulubionych gatunków; userId bez gatunków nie występują w wyniku. */
	Map<UUID, Set<UUID>> favoriteGenreIdsByUser(Collection<UUID> userIds);
}
