package com.songly.profile;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserGenreRepository extends JpaRepository<UserGenre, UserGenreId> {

	List<UserGenre> findByUserId(UUID userId);

	void deleteByUserId(UUID userId);
}
