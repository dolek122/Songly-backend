package com.songly.profile;

import com.songly.common.media.SanitizedImage;
import com.songly.common.storage.ObjectStorage;
import com.songly.identity.SuspendedAccounts;
import com.songly.profile.dto.GenreResponse;
import com.songly.profile.dto.ProfileResponse;
import com.songly.profile.dto.ProfileSummaryResponse;
import com.songly.profile.dto.PublicProfileResponse;
import com.songly.profile.dto.UpdateProfileRequest;
import com.songly.profile.dto.UserSearchResponse;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProfileService implements ProfileDirectory {

	static final int MAX_SUMMARY_IDS = 100;

	static final String DEFAULT_DISPLAY_NAME_PREFIX = "Użytkownik";

	private final ProfileRepository profileRepository;
	private final GenreRepository genreRepository;
	private final UserGenreRepository userGenreRepository;
	private final ObjectStorage objectStorage;
	private final ApplicationEventPublisher eventPublisher;
	private final SuspendedAccounts suspendedAccounts;
	private final GenrePopularityCache genrePopularityCache;

	ProfileService(ProfileRepository profileRepository, GenreRepository genreRepository,
			UserGenreRepository userGenreRepository, ObjectStorage objectStorage,
			ApplicationEventPublisher eventPublisher, SuspendedAccounts suspendedAccounts,
			GenrePopularityCache genrePopularityCache) {
		this.profileRepository = profileRepository;
		this.genreRepository = genreRepository;
		this.userGenreRepository = userGenreRepository;
		this.objectStorage = objectStorage;
		this.eventPublisher = eventPublisher;
		this.suspendedAccounts = suspendedAccounts;
		this.genrePopularityCache = genrePopularityCache;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void createDefaultProfile(UUID userId) {
		if (profileRepository.existsByUserId(userId)) {
			return;
		}
		Instant now = Instant.now();
		profileRepository.save(new Profile(UUID.randomUUID(), userId, defaultDisplayName(), null, null, now, now));
	}

	@Transactional(readOnly = true)
	ProfileResponse getProfile(UUID userId) {
		return toResponse(profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new));
	}

	@Transactional(readOnly = true)
	PublicProfileResponse getPublicProfile(UUID userId) {
		if (suspendedAccounts.suspendedUserIds().contains(userId)) {
			throw new ProfileNotFoundException();
		}
		Profile profile = profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new);
		return new PublicProfileResponse(profile.getUserId(), profile.getDisplayName(), profile.getBio(),
				avatarUrl(profile), getFavoriteGenres(userId));
	}

	@Transactional(readOnly = true)
	Page<UserSearchResponse> searchUsers(UUID requesterId, String query, Pageable pageable) {
		String trimmed = query == null ? "" : query.trim();
		if (trimmed.isEmpty()) {
			return Page.empty(pageable);
		}
		Set<UUID> excluded = new HashSet<>(suspendedAccounts.suspendedUserIds());
		excluded.add(requesterId);
		return profileRepository.searchByDisplayName(escapeLike(trimmed), excluded, pageable).map(
				profile -> new UserSearchResponse(profile.getUserId(), profile.getDisplayName(), avatarUrl(profile)));
	}

	private static String escapeLike(String query) {
		return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	@Transactional
	ProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
		Profile profile = profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new);
		profile.update(request.displayName(), request.bio());
		return toResponse(profile);
	}

	@Transactional(readOnly = true)
	Page<GenreResponse> listGenres(Pageable pageable) {
		return genreRepository.findAllByOrderByNameAsc(pageable)
				.map(genre -> new GenreResponse(genre.getId(), genre.getName()));
	}

	@Transactional
	void setFavoriteGenres(UUID userId, Set<UUID> genreIds) {
		if (genreRepository.countByIdIn(genreIds) != genreIds.size()) {
			throw new UnknownGenreException();
		}
		userGenreRepository.deleteByUserId(userId);
		Instant now = Instant.now();
		userGenreRepository.saveAll(genreIds.stream().map(genreId -> new UserGenre(userId, genreId, now)).toList());
		eventPublisher.publishEvent(new FavoriteGenresChangedEvent(userId));
	}

	@Transactional(readOnly = true)
	List<GenreResponse> getFavoriteGenres(UUID userId) {
		List<UUID> genreIds = userGenreRepository.findByUserId(userId).stream().map(UserGenre::getGenreId).toList();
		return genreRepository.findAllById(genreIds).stream()
				.map(genre -> new GenreResponse(genre.getId(), genre.getName())).toList();
	}

	@Transactional(readOnly = true)
	List<ProfileSummaryResponse> profileSummaries(Set<UUID> userIds) {
		if (userIds.size() > MAX_SUMMARY_IDS) {
			throw new TooManyUserIdsException(MAX_SUMMARY_IDS);
		}
		Set<UUID> suspended = suspendedAccounts.suspendedUserIds();
		return profileRepository
				.findByUserIdIn(userIds).stream().map(profile -> new ProfileSummaryResponse(profile.getUserId(),
						profile.getDisplayName(), avatarUrl(profile), suspended.contains(profile.getUserId())))
				.toList();
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
		return profileRepository.findByUserIdIn(userIds).stream().filter(profile -> profile.getAvatarKey() != null)
				.collect(Collectors.toMap(Profile::getUserId, this::avatarUrl));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Long> genreUserCounts(Collection<UUID> genreIds) {
		if (genreIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Long> counts = popularity().counts();
		return genreIds.stream().filter(counts::containsKey).collect(Collectors.toMap(genreId -> genreId, counts::get));
	}

	@Override
	@Transactional(readOnly = true)
	public long usersWithAnyGenreCount() {
		return popularity().totalUsers();
	}

	private GenrePopularityCache.Snapshot popularity() {
		return genrePopularityCache.read().orElseGet(() -> {
			GenrePopularityCache.Snapshot snapshot = new GenrePopularityCache.Snapshot(
					userGenreRepository.countUsersByGenre().stream()
							.collect(Collectors.toMap(UserGenreRepository.GenreCount::getGenreId,
									UserGenreRepository.GenreCount::getUserCount)),
					userGenreRepository.countDistinctUsers());
			genrePopularityCache.write(snapshot);
			return snapshot;
		});
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
	ProfileResponse uploadAvatar(UUID userId, byte[] content) {
		SanitizedImage image = SanitizedImage.from(content);
		Profile profile = profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new);

		String key = "avatars/%s/%s.%s".formatted(userId, UUID.randomUUID(), image.type().extension());
		objectStorage.put(key, image.content(), image.type().contentType());
		replaceAvatarKey(profile, key);
		return toResponse(profile);
	}

	@Transactional
	void deleteAvatar(UUID userId) {
		replaceAvatarKey(profileRepository.findByUserId(userId).orElseThrow(ProfileNotFoundException::new), null);
	}

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
		eventPublisher.publishEvent(new FavoriteGenresChangedEvent(userId));
		profileRepository.findByUserId(userId).ifPresent(profile -> {
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

	private static String defaultDisplayName() {
		return "%s %06d".formatted(DEFAULT_DISPLAY_NAME_PREFIX, ThreadLocalRandom.current().nextInt(1_000_000));
	}

	private ProfileResponse toResponse(Profile profile) {
		return new ProfileResponse(profile.getUserId(), profile.getDisplayName(), profile.getBio(), avatarUrl(profile));
	}
}
