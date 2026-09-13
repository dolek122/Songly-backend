package com.songly.profile;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface UserGenreRepository extends JpaRepository<UserGenre, UserGenreId> {

	List<UserGenre> findByUserId(UUID userId);

	List<UserGenre> findByUserIdIn(Collection<UUID> userIds);

	List<UserGenre> findByGenreIdIn(Collection<UUID> genreIds);

	/**
	 * Popularność <b>wszystkich</b> gatunków jednym zapytaniem (ADR 0029) — bez
	 * wczytywania wierszy, bo interesują nas same liczby, a {@code user_genres}
	 * jest najliczniejszą tabelą tego modułu.
	 *
	 * <p>
	 * Bez filtra po gatunkach, odkąd wynik idzie do cache'a (ADR 0032): zawężenie
	 * do podzbioru z jednego żądania dałoby wpis użyteczny tylko dla żądań o
	 * dokładnie ten podzbiór, a pełna tabela to dziś piętnaście wierszy.
	 */
	@Query("select g.genreId as genreId, count(g) as userCount from UserGenre g group by g.genreId")
	List<GenreCount> countUsersByGenre();

	/** Mianownik popularności: ilu userów ma w ogóle jakiś gatunek. */
	@Query("select count(distinct g.userId) from UserGenre g")
	long countDistinctUsers();

	void deleteByUserId(UUID userId);

	interface GenreCount {

		UUID getGenreId();

		long getUserCount();
	}
}
