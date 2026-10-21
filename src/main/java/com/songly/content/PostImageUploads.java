package com.songly.content;

import com.songly.common.media.SanitizedImage;
import com.songly.common.storage.ObjectStorage;
import com.songly.content.dto.PostImageResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Zdjęcia postów od wgrania do publikacji posta (ADR 0048).
 *
 * <p>
 * Wgranie to osobne żądanie przed publikacją (ADR 0010), więc plik istnieje,
 * zanim cokolwiek go wskazuje. Do ADR 0048 nic go potem nie liczyło ani nie
 * sprzątało: każde porzucone zdjęcie zostawało w magazynie na zawsze, a jedynym
 * ograniczeniem był limit zapisów (60 na minutę), czyli setki gigabajtów na
 * dobę z jednego konta. Rejestr {@link PostImageUpload} domyka obie dziury.
 */
@Service
@EnableConfigurationProperties(PostImageProperties.class)
class PostImageUploads {

	private static final String KEY_PREFIX = "posts/";

	private final PostImageUploadRepository repository;
	private final ObjectStorage objectStorage;
	private final ApplicationEventPublisher eventPublisher;
	private final PostImageProperties properties;

	PostImageUploads(PostImageUploadRepository repository, ObjectStorage objectStorage,
			ApplicationEventPublisher eventPublisher, PostImageProperties properties) {
		this.repository = repository;
		this.objectStorage = objectStorage;
		this.eventPublisher = eventPublisher;
		this.properties = properties;
	}

	/**
	 * Wgrywa zdjęcie i zwraca jego klucz, jeszcze przed opublikowaniem posta. Do
	 * magazynu trafia plik bez metadanych, w tym bez GPS-u ({@link SanitizedImage},
	 * ADR 0047).
	 *
	 * <p>
	 * Ponad {@link PostImageProperties#getMaxPending()} oczekujących najstarsze
	 * znikają. Plik ląduje w magazynie przed commitem, tak jak avatar: wycofanie
	 * transakcji zostawia osierocony plik, a nie wiersz bez pliku.
	 */
	@Transactional
	PostImageResponse upload(UUID ownerId, byte[] content) {
		SanitizedImage image = SanitizedImage.from(content);
		String key = "%s%s/%s.%s".formatted(KEY_PREFIX, ownerId, UUID.randomUUID(), image.type().extension());
		objectStorage.put(key, image.content(), image.type().contentType());
		repository.save(new PostImageUpload(key, ownerId, Instant.now()));

		List<String> pending = repository.findKeysByOwnerIdNewestFirst(ownerId);
		for (String evicted : pending.subList(Math.min(properties.getMaxPending(), pending.size()), pending.size())) {
			if (repository.deleteByImageKey(evicted) == 1) {
				eventPublisher.publishEvent(new OrphanedPostImageEvent(evicted));
			}
		}
		return new PostImageResponse(key, objectStorage.publicUrl(key));
	}

	/**
	 * Przypina zdjęcie do publikowanego posta, zużywając wiersz rejestru.
	 *
	 * <p>
	 * Rejestr zastępuje dawne sprawdzanie kształtu klucza: przechodzi wyłącznie
	 * klucz, który sami wydaliśmy temu użytkownikowi i który jeszcze nie został
	 * przypięty. Cudzy, zmyślony albo z {@code ..} w środku nie ma wiersza. Ten sam
	 * klucz nie trafi też do dwóch postów — wcześniej trafiał, a usunięcie jednego
	 * kasowało plik drugiemu.
	 *
	 * <p>
	 * W transakcji publikacji ({@code MANDATORY}), żeby odmowa w dalszej części
	 * publikacji oddawała wiersz razem z resztą zmian.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	String claim(UUID ownerId, String imageKey) {
		if (imageKey == null || imageKey.isBlank()) {
			return null;
		}
		if (repository.deleteByImageKeyAndOwnerId(imageKey, ownerId) == 0) {
			throw new UnknownPostImageException();
		}
		return imageKey;
	}

	/**
	 * Część kasowania konta — w jego transakcji, przed usunięciem wiersza
	 * {@code users}.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void deleteAllOf(UUID ownerId) {
		List<String> keys = repository.findKeysByOwnerIdNewestFirst(ownerId);
		repository.deleteByOwnerId(ownerId);
		keys.forEach(key -> eventPublisher.publishEvent(new OrphanedPostImageEvent(key)));
	}

	/**
	 * Usuwa zdjęcia, które czekały na post dłużej niż
	 * {@link PostImageProperties#getPendingTtl()}.
	 *
	 * <p>
	 * <b>Najpierw wiersz, potem plik</b>, każdy wiersz we własnej transakcji. Plik
	 * kasuje tylko ten, komu usunięcie wiersza zwróciło 1, więc zdjęcie przypięte w
	 * tej samej chwili do posta zostaje. Odwrotna kolejność dawałaby w takim
	 * wyścigu post wskazujący plik, którego nie ma.
	 *
	 * <p>
	 * Błąd magazynu przerywa przebieg, zamiast lecieć dalej: wiersz już zniknął,
	 * więc jego plik zostaje osierocony. Przy awarii magazynu dalsze próby
	 * osierociłyby wszystkie pozostałe, a tak zostaje jeden.
	 */
	int removeAbandoned(Instant now) {
		Instant cutoff = now.minus(properties.getPendingTtl());
		int batchSize = properties.getCleanupBatchSize();
		int removed = 0;
		List<String> keys;
		do {
			keys = repository.findKeysCreatedBefore(cutoff, Limit.of(batchSize));
			for (String key : keys) {
				if (repository.deleteByImageKey(key) == 1) {
					objectStorage.delete(key);
					removed++;
				}
			}
		} while (keys.size() == batchSize);
		return removed;
	}
}
