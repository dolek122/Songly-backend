package com.songly.profile;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserGenreRepository extends JpaRepository<UserGenre, UserGenreId> {

	List<UserGenre> findByUserId(UUID userId);

	List<UserGenre> findByUserIdIn(Collection<UUID> userIds);

	List<UserGenre> findByGenreIdIn(Collection<UUID> genreIds);

	/**
	 * Popularność gatunków jednym zapytaniem (ADR 0029) — bez wczytywania wierszy,
	 * bo interesują nas same liczby, a {@code user_genres} jest najliczniejszą
	 * tabelą tego modułu.
	 */
	@Query("select g.genreId as genreId, count(g) as userCount from UserGenre g where g.genreId in :genreIds group by g.genreId")
	List<GenreCount> countUsersByGenreIdIn(@Param("genreIds") Collection<UUID> genreIds);

	/** Mianownik popularności: ilu userów ma w ogóle jakiś gatunek. */
	@Query("select count(distinct g.userId) from UserGenre g")
	long countDistinctUsers();

	void deleteByUserId(UUID userId);

	interface GenreCount {

		UUID getGenreId();

		long getUserCount();
	}
}
