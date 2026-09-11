package com.songly.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationMailPreferenceRepository extends JpaRepository<NotificationMailPreference, UUID> {

	/**
	 * Jedno zapytanie na wysyłkę, nie jedno na adresata — mail o zgłoszeniu idzie
	 * do wszystkich moderatorów naraz.
	 */
	List<NotificationMailPreference> findByUserIdIn(Collection<UUID> userIds);

	void deleteByUserId(UUID userId);
}
