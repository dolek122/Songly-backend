package com.songly.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PushDeviceService {

	private final PushDeviceRepository repository;
	private final int maxDevicesPerUser;

	PushDeviceService(PushDeviceRepository repository,
			@Value("${songly.notification.push.max-devices-per-user:10}") int maxDevicesPerUser) {
		this.repository = repository;
		this.maxDevicesPerUser = maxDevicesPerUser;
	}

	@Transactional
	void register(UUID userId, String token, PushPlatform platform) {
		Instant now = Instant.now();
		repository.findById(token).ifPresentOrElse(device -> device.assignTo(userId, platform, now),
				() -> repository.save(new PushDevice(token, userId, platform, now)));
		repository.flush();
		List<PushDevice> devices = repository.findByUserIdOrderByUpdatedAtDesc(userId);
		if (devices.size() > maxDevicesPerUser) {
			repository.deleteAll(devices.subList(maxDevicesPerUser, devices.size()));
		}
	}

	@Transactional
	void unregister(UUID userId, String token) {
		repository.deleteByTokenAndUserId(token, userId);
	}

	@Transactional(readOnly = true)
	List<String> tokensOf(UUID userId) {
		return repository.findByUserId(userId).stream().map(PushDevice::getToken).toList();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void forget(String token) {
		repository.deleteById(token);
	}

	@Transactional
	void deleteAllFor(UUID userId) {
		repository.deleteByUserId(userId);
	}
}
