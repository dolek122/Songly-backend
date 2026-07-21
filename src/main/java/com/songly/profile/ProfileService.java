package com.songly.profile;

import com.songly.profile.dto.GenreResponse;
import com.songly.profile.dto.ProfileResponse;
import com.songly.profile.dto.UpdateProfileRequest;
import com.songly.profile.dto.UserSearchResponse;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

	ProfileService(ProfileRepository profileRepository, GenreRepository genreRepository,
			UserGenreRepository userGenreRepository) {
		this.profileRepository = profileRepository;
		this.genreRepository = genreRepository;
		this.userGenreRepository = userGenreRepository;
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
		return profileRepository.findByDisplayNameContainingIgnoreCaseAndUserIdNot(trimmed, requesterId, pageable)
				.map(profile -> new UserSearchResponse(profile.getUserId(), profile.getDisplayName(),
						profile.getAvatarUrl()));
	}

	@Transactional
	ProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
		Profile profile = profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new);
		profile.update(request.displayName(), request.bio(), request.avatarUrl());
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
	public Set<UUID> userIdsWithAnyGenre(Collection<UUID> genreIds) {
		if (genreIds.isEmpty()) {
			return Set.of();
		}
		return userGenreRepository.findByGenreIdIn(genreIds).stream().map(UserGenre::getUserId)
				.collect(Collectors.toSet());
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		userGenreRepository.deleteByUserId(userId);
		profileRepository.findByUserId(userId).ifPresent(profileRepository::delete);
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
		return new ProfileResponse(profile.getUserId(), profile.getDisplayName(), profile.getBio(),
				profile.getAvatarUrl());
	}
}
