package com.songly.profile;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserGenreRepository extends JpaRepository<UserGenre, UserGenreId> {

	List<UserGenre> findByUserId(UUID userId);

	List<UserGenre> findByUserIdIn(Collection<UUID> userIds);

	List<UserGenre> findByGenreIdIn(Collection<UUID> genreIds);

	void deleteByUserId(UUID userId);
}
