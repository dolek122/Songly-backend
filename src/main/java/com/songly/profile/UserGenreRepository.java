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

	@Query("select g.genreId as genreId, count(g) as userCount from UserGenre g group by g.genreId")
	List<GenreCount> countUsersByGenre();

	@Query("select count(distinct g.userId) from UserGenre g")
	long countDistinctUsers();

	void deleteByUserId(UUID userId);

	interface GenreCount {

		UUID getGenreId();

		long getUserCount();
	}
}
