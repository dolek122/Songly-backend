package com.songly.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Rejestr urządzeń push (ADR 0040). */
@Service
class PushDeviceService {

	private final PushDeviceRepository repository;
	private final int maxDevicesPerUser;

	PushDeviceService(PushDeviceRepository repository,
			@Value("${songly.notification.push.max-devices-per-user:10}") int maxDevicesPerUser) {
		this.repository = repository;
		this.maxDevicesPerUser = maxDevicesPerUser;
	}

	/**
	 * Idempotentne — klient zgłasza token przy każdym starcie. Token znany pod
	 * innym kontem przepinamy: to ten sam telefon, na którym ktoś się przelogował,
	 * a poprzedni właściciel nie może dalej dostawać tam swoich powiadomień.
	 *
	 * <p>
	 * Nadmiar ponad limit przycinamy od najdawniej zgłoszonych. Bez tego tabela
	 * rosłaby z każdą reinstalacją, a każdy push szedłby też do telefonów, których
	 * nikt już nie ma — FCM odpowiada na nie {@code UNREGISTERED} dopiero po jakimś
	 * czasie.
	 */
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

	/**
	 * Cudzy albo nieznany token to brak efektu, nie błąd: odpowiedź nie może mówić,
	 * czy dany token należy do kogoś innego.
	 */
	@Transactional
	void unregister(UUID userId, String token) {
		repository.deleteByTokenAndUserId(token, userId);
	}

	@Transactional(readOnly = true)
	List<String> tokensOf(UUID userId) {
		return repository.findByUserId(userId).stream().map(PushDevice::getToken).toList();
	}

	/**
	 * Wołane z wysyłki, po commicie powiadomienia — stąd własna transakcja (ten sam
	 * wzorzec co {@code NotificationService.record}).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void forget(String token) {
		repository.deleteById(token);
	}

	@Transactional
	void deleteAllFor(UUID userId) {
		repository.deleteByUserId(userId);
	}
}
