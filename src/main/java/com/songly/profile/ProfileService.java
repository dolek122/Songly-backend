package com.songly.profile;

import com.songly.common.media.ImageType;
import com.songly.common.media.UnsupportedImageException;
import com.songly.common.storage.ObjectStorage;
import com.songly.profile.dto.GenreResponse;
import com.songly.profile.dto.ProfileResponse;
import com.songly.profile.dto.PublicProfileResponse;
import com.songly.profile.dto.UpdateProfileRequest;
import com.songly.profile.dto.UserSearchResponse;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProfileService implements ProfileDirectory {

	private final ProfileRepository profileRepository;
	private final GenreRepository genreRepository;
	private final UserGenreRepository userGenreRepository;
	private final ObjectStorage objectStorage;
	private final ApplicationEventPublisher eventPublisher;

	ProfileService(ProfileRepository profileRepository, GenreRepository genreRepository,
			UserGenreRepository userGenreRepository, ObjectStorage objectStorage,
			ApplicationEventPublisher eventPublisher) {
		this.profileRepository = profileRepository;
		this.genreRepository = genreRepository;
		this.userGenreRepository = userGenreRepository;
		this.objectStorage = objectStorage;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * REQUIRES_NEW celowo: wywoływane z {@code AFTER_COMMIT} listenera modułu
	 * identity, gdzie transakcja rejestracji już się zakończyła — REQUIRED
	 * dołączyłby się do jej resztek zamiast otworzyć nową, a wstawiany profil nigdy
	 * by się nie zacommitował.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void createDefaultProfile(UUID userId, String email) {
		if (profileRepository.existsByUserId(userId)) {
			return;
		}
		Instant now = Instant.now();
		profileRepository.save(new Profile(UUID.randomUUID(), userId, defaultDisplayName(email), null, null, now, now));
	}

	@Transactional(readOnly = true)
	ProfileResponse getProfile(UUID userId) {
		return toResponse(profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new));
	}

	/**
	 * Publiczny profil dowolnego użytkownika (do oglądania cudzych profili). Rzuca
	 * {@link ProfileNotFoundException} (→ 404) gdy profilu nie ma. Ulubione gatunki
	 * dokładamy tą samą ścieżką co dla własnego profilu — to dane publiczne.
	 */
	@Transactional(readOnly = true)
	PublicProfileResponse getPublicProfile(UUID userId) {
		Profile profile = profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new);
		return new PublicProfileResponse(profile.getUserId(), profile.getDisplayName(), profile.getBio(),
				avatarUrl(profile), getFavoriteGenres(userId));
	}

	/**
	 * Wyszukiwanie użytkowników po nazwie wyświetlanej — na potrzeby dodawania
	 * znajomych (moduł social-graph potrzebuje {@code addresseeId}). Pusty/biały
	 * query zwraca pustą stronę, żeby nie wysypywać wszystkich użytkowników. Sam
	 * pytający jest wykluczany z wyników. Wyszukiwarka celowo nie filtruje po
	 * blokadach (to dane modułu social, poza granicą §2) — wysłanie zaproszenia do
	 * zablokowanego i tak odrzuci moduł social.
	 */
	@Transactional(readOnly = true)
	Page<UserSearchResponse> searchUsers(UUID requesterId, String query, Pageable pageable) {
		String trimmed = query == null ? "" : query.trim();
		if (trimmed.isEmpty()) {
			return Page.empty(pageable);
		}
		return profileRepository.findByDisplayNameContainingIgnoreCaseAndUserIdNot(trimmed, requesterId, pageable).map(
				profile -> new UserSearchResponse(profile.getUserId(), profile.getDisplayName(), avatarUrl(profile)));
	}

	@Transactional
	ProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
		Profile profile = profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new);
		profile.update(request.displayName(), request.bio());
		return toResponse(profile);
	}

	@Transactional(readOnly = true)
	Page<GenreResponse> listGenres(Pageable pageable) {
		return genreRepository.findAll(pageable).map(genre -> new GenreResponse(genre.getId(), genre.getName()));
	}

	@Transactional
	void setFavoriteGenres(UUID userId, Set<UUID> genreIds) {
		if (genreRepository.countByIdIn(genreIds) != genreIds.size()) {
			throw new UnknownGenreException();
		}
		userGenreRepository.deleteByUserId(userId);
		Instant now = Instant.now();
		userGenreRepository.saveAll(genreIds.stream().map(genreId -> new UserGenre(userId, genreId, now)).toList());
	}

	@Transactional(readOnly = true)
	List<GenreResponse> getFavoriteGenres(UUID userId) {
		List<UUID> genreIds = userGenreRepository.findByUserId(userId).stream().map(UserGenre::getGenreId).toList();
		return genreRepository.findAllById(genreIds).stream()
				.map(genre -> new GenreResponse(genre.getId(), genre.getName())).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> displayNames(Collection<UUID> userIds) {
		return profileRepository.findByUserIdIn(userIds).stream()
				.collect(Collectors.toMap(Profile::getUserId, Profile::getDisplayName));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> avatarUrls(Collection<UUID> userIds) {
		// Filtrujemy null-e: Collectors.toMap nie przyjmuje null wartości, a i tak
		// „brak avatara" reprezentujemy nieobecnością klucza (konsument dostaje null).
		return profileRepository.findByUserIdIn(userIds).stream().filter(profile -> profile.getAvatarKey() != null)
				.collect(Collectors.toMap(Profile::getUserId, this::avatarUrl));
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> userIdsWithAnyGenre(Collection<UUID> genreIds) {
		if (genreIds.isEmpty()) {
			return Set.of();
		}
		return userGenreRepository.findByGenreIdIn(genreIds).stream().map(UserGenre::getUserId)
				.collect(Collectors.toSet());
	}

	/**
	 * Wgrywa nowy avatar i zwraca zaktualizowany profil.
	 *
	 * <p>
	 * Format rozpoznajemy <b>po zawartości pliku</b>, nie po nagłówku
	 * {@code Content-Type} ani rozszerzeniu — jedno i drugie podaje klient (patrz
	 * {@link ImageType}). Rozszerzenie w kluczu bierze się z rozpoznanego typu, a
	 * nie z nazwy, którą przysłał przeglądarka: inaczej dałoby się zapisać plik
	 * jako {@code .html} i zdobyć adres, spod którego serwujemy cudzy HTML na
	 * własnej domenie.
	 *
	 * <p>
	 * Losowy człon w kluczu, zamiast stałego {@code avatars/{userId}}, bo pod
	 * starym adresem stoi cache CDN-u — nadpisanie w miejscu pokazywałoby przez
	 * wiele godzin poprzednie zdjęcie i wyglądało jak zignorowana zmiana.
	 *
	 * <p>
	 * Plik ląduje w magazynie <b>przed</b> commitem, więc wycofanie transakcji
	 * zostawia go osieroconym. To świadomie ta strona pomyłki: odwrotna kolejność
	 * dawałaby wiersz wskazujący na plik, którego nie ma.
	 */
	@Transactional
	ProfileResponse uploadAvatar(UUID userId, byte[] content) {
		ImageType type = ImageType.detect(content).orElseThrow(UnsupportedImageException::new);
		Profile profile = profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new);

		String key = "avatars/%s/%s.%s".formatted(userId, UUID.randomUUID(), type.extension());
		objectStorage.put(key, content, type.contentType());
		replaceAvatarKey(profile, key);
		return toResponse(profile);
	}

	@Transactional
	void deleteAvatar(UUID userId) {
		replaceAvatarKey(profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new), null);
	}

	/**
	 * Ustawia nowy klucz i zgłasza poprzedni do skasowania. Zgłoszenie, a nie
	 * skasowanie od razu — plik ginie dopiero po commicie
	 * ({@link OrphanedAvatarEvent}).
	 */
	private void replaceAvatarKey(Profile profile, String newKey) {
		String previousKey = profile.getAvatarKey();
		profile.changeAvatarKey(newKey);
		if (previousKey != null && !previousKey.equals(newKey)) {
			eventPublisher.publishEvent(new OrphanedAvatarEvent(previousKey));
		}
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		userGenreRepository.deleteByUserId(userId);
		profileRepository.findByUserId(userId).ifPresent(profile -> {
			// Zgłoszenie pliku do skasowania musi wyjść PRZED usunięciem wiersza, bo
			// potem nie ma już skąd odczytać klucza. Sam plik ginie i tak dopiero po
			// commicie — inaczej wycofane kasowanie konta zostawiłoby profil bez zdjęcia.
			replaceAvatarKey(profile, null);
			profileRepository.delete(profile);
		});
	}

	private String avatarUrl(Profile profile) {
		return profile.getAvatarKey() == null ? null : objectStorage.publicUrl(profile.getAvatarKey());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Set<UUID>> favoriteGenreIdsByUser(Collection<UUID> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return userGenreRepository.findByUserIdIn(userIds).stream().collect(Collectors.groupingBy(UserGenre::getUserId,
				Collectors.mapping(UserGenre::getGenreId, Collectors.toSet())));
	}

	private String defaultDisplayName(String email) {
		int at = email.indexOf('@');
		return at > 0 ? email.substring(0, at) : email;
	}

	private ProfileResponse toResponse(Profile profile) {
		return new ProfileResponse(profile.getUserId(), profile.getDisplayName(), profile.getBio(), avatarUrl(profile));
	}
}
