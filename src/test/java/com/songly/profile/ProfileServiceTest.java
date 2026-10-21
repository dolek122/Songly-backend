package com.songly.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.common.storage.ObjectStorage;
import com.songly.profile.dto.GenreResponse;
import com.songly.profile.dto.ProfileResponse;
import com.songly.profile.dto.UpdateProfileRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

	@Mock
	private ProfileRepository profileRepository;

	@Mock
	private GenreRepository genreRepository;

	@Mock
	private UserGenreRepository userGenreRepository;

	@Mock
	private ObjectStorage objectStorage;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private com.songly.identity.SuspendedAccounts suspendedAccounts;

	@Mock
	private GenrePopularityCache genrePopularityCache;

	private ProfileService profileService;

	@BeforeEach
	void setUp() {
		profileService = new ProfileService(profileRepository, genreRepository, userGenreRepository, objectStorage,
				eventPublisher, suspendedAccounts, genrePopularityCache);
	}

	@Test
	void createDefaultProfile_createsProfileWithNeutralDisplayName_whenNoneExists() {
		UUID userId = UUID.randomUUID();
		when(profileRepository.existsByUserId(userId)).thenReturn(false);

		profileService.createDefaultProfile(userId);

		ArgumentCaptor<Profile> captor = ArgumentCaptor.forClass(Profile.class);
		verify(profileRepository).save(captor.capture());
		// Nie część adresu e-mail (ADR 0046) — ta była publiczna i wyszukiwalna.
		assertThat(captor.getValue().getDisplayName()).matches("Użytkownik \\d{6}");
		assertThat(captor.getValue().getUserId()).isEqualTo(userId);
	}

	@Test
	void createDefaultProfile_doesNothing_whenProfileAlreadyExists() {
		UUID userId = UUID.randomUUID();
		when(profileRepository.existsByUserId(userId)).thenReturn(true);

		profileService.createDefaultProfile(userId);

		verify(profileRepository, never()).save(any());
	}

	@Test
	void getProfile_returnsResponse_whenFound() {
		UUID userId = UUID.randomUUID();
		Profile profile = new Profile(UUID.randomUUID(), userId, "Ada", "bio", null, Instant.now(), Instant.now());
		when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));

		ProfileResponse response = profileService.getProfile(userId);

		assertThat(response.displayName()).isEqualTo("Ada");
		assertThat(response.bio()).isEqualTo("bio");
	}

	@Test
	void getProfile_throws_whenNotFound() {
		UUID userId = UUID.randomUUID();
		when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> profileService.getProfile(userId)).isInstanceOf(ProfileNotFoundException.class);
	}

	@Test
	void updateProfile_updatesFields_whenFound() {
		UUID userId = UUID.randomUUID();
		Profile profile = new Profile(UUID.randomUUID(), userId, "Old", "old bio", null, Instant.now(), Instant.now());
		when(profileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));

		ProfileResponse response = profileService.updateProfile(userId, new UpdateProfileRequest("New", "new bio"));

		assertThat(response.displayName()).isEqualTo("New");
		assertThat(response.bio()).isEqualTo("new bio");
		// Avatara nie da się już podmienić edycją profilu — ustawia go wyłącznie
		// upload pliku (ADR 0010).
		assertThat(response.avatarUrl()).isNull();
	}

	@Test
	void updateProfile_throws_whenNotFound() {
		UUID userId = UUID.randomUUID();
		when(profileRepository.findByUserId(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> profileService.updateProfile(userId, new UpdateProfileRequest("New", null)))
				.isInstanceOf(ProfileNotFoundException.class);
	}

	@Test
	@SuppressWarnings("unchecked")
	void setFavoriteGenres_replacesGenres_whenAllKnown() {
		UUID userId = UUID.randomUUID();
		Set<UUID> genreIds = Set.of(UUID.randomUUID(), UUID.randomUUID());
		when(genreRepository.countByIdIn(genreIds)).thenReturn((long) genreIds.size());

		profileService.setFavoriteGenres(userId, genreIds);

		verify(userGenreRepository).deleteByUserId(userId);
		ArgumentCaptor<List<UserGenre>> captor = ArgumentCaptor.forClass(List.class);
		verify(userGenreRepository).saveAll(captor.capture());
		assertThat(captor.getValue()).hasSize(genreIds.size());
	}

	@Test
	void setFavoriteGenres_throws_whenGenreUnknown() {
		UUID userId = UUID.randomUUID();
		Set<UUID> genreIds = Set.of(UUID.randomUUID());
		when(genreRepository.countByIdIn(genreIds)).thenReturn(0L);

		assertThatThrownBy(() -> profileService.setFavoriteGenres(userId, genreIds))
				.isInstanceOf(UnknownGenreException.class);

		verify(userGenreRepository, never()).deleteByUserId(any());
		verify(userGenreRepository, never()).saveAll(anyCollection());
	}

	@Test
	void getFavoriteGenres_mapsToResponses() {
		UUID userId = UUID.randomUUID();
		UUID genreId = UUID.randomUUID();
		when(userGenreRepository.findByUserId(userId))
				.thenReturn(List.of(new UserGenre(userId, genreId, Instant.now())));
		when(genreRepository.findAllById(List.of(genreId))).thenReturn(List.of(new Genre(genreId, "Rock")));

		List<GenreResponse> result = profileService.getFavoriteGenres(userId);

		assertThat(result).containsExactly(new GenreResponse(genreId, "Rock"));
	}
}
