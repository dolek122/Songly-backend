package com.songly.social;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface FriendRequestRepository extends JpaRepository<FriendRequest, UUID> {

	Optional<FriendRequest> findByRequesterIdAndAddresseeIdAndStatus(UUID requesterId, UUID addresseeId,
			FriendRequestStatus status);

	Page<FriendRequest> findByAddresseeIdAndStatusOrderByCreatedAtDesc(UUID addresseeId, FriendRequestStatus status,
			Pageable pageable);

	Page<FriendRequest> findByRequesterIdAndStatusOrderByCreatedAtDesc(UUID requesterId, FriendRequestStatus status,
			Pageable pageable);

	void deleteByRequesterIdAndAddresseeIdAndStatus(UUID requesterId, UUID addresseeId, FriendRequestStatus status);

	void deleteByRequesterIdOrAddresseeId(UUID requesterId, UUID addresseeId);
}
