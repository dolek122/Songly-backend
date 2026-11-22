package com.songly.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationMailPreferenceRepository extends JpaRepository<NotificationMailPreference, UUID> {

	List<NotificationMailPreference> findByUserIdIn(Collection<UUID> userIds);

	void deleteByUserId(UUID userId);
}
