package com.songly.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

	Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

	List<Notification> findByUserIdAndReadAtIsNull(UUID userId);

	long countByUserIdAndReadAtIsNull(UUID userId);
}
