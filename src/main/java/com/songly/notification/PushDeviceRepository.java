package com.songly.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PushDeviceRepository extends JpaRepository<PushDevice, String> {

	List<PushDevice> findByUserId(UUID userId);

	List<PushDevice> findByUserIdOrderByUpdatedAtDesc(UUID userId);

	/**
	 * Wsadowo, bo na tej encji nie wisi żadne zdarzenie cyklu życia (pułapka z
	 * Claude.md §8 jej nie dotyczy).
	 */
	@Modifying
	@Query("delete from PushDevice d where d.userId = :userId")
	void deleteByUserId(UUID userId);

	@Modifying
	@Query("delete from PushDevice d where d.token = :token and d.userId = :userId")
	void deleteByTokenAndUserId(String token, UUID userId);
}
