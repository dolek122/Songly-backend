package com.songly.social;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface FriendshipRepository extends JpaRepository<Friendship, FriendshipId> {

	Page<Friendship> findByUserAIdOrUserBIdOrderByCreatedAtDesc(UUID userAId, UUID userBId, Pageable pageable);

	List<Friendship> findByUserAIdOrUserBId(UUID userAId, UUID userBId);
}
