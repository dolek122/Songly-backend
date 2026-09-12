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

	/**
	 * URL-e avatarów. userId bez ustawionego avatara (null) nie występują w wyniku
	 * — konsument dostaje wtedy {@code null} z {@code get(userId)}.
	 */
	Map<UUID, String> avatarUrls(Collection<UUID> userIds);

	/** Użytkownicy mający wśród ulubionych którykolwiek z podanych gatunków. */
	Set<UUID> userIdsWithAnyGenre(Collection<UUID> genreIds);

	/**
	 * Pełne zbiory ulubionych gatunków; userId bez gatunków nie występują w wyniku.
	 */
	Map<UUID, Set<UUID>> favoriteGenreIdsByUser(Collection<UUID> userIds);

	/**
	 * Ilu użytkowników wskazało każdy z podanych gatunków — popularność gatunku
	 * (ADR 0029). Gatunki bez ani jednego wskazania nie występują w wyniku.
	 *
	 * <p>
	 * Służy do zważenia dopasowania: wspólny gatunek niszowy mówi o ludziach
	 * znacznie więcej niż wspólny gatunek, który wybrała połowa serwisu.
	 */
	Map<UUID, Long> genreUserCounts(Collection<UUID> genreIds);

	/**
	 * Ilu użytkowników ma w ogóle jakiekolwiek ulubione gatunki — mianownik
	 * popularności z {@link #genreUserCounts(Collection)}. Liczony globalnie, bo
	 * „niszowy" znaczy „rzadki w całym serwisie", nie „rzadki w tej próbce".
	 */
	long usersWithAnyGenreCount();
}
