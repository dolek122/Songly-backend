package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpotifyProviderTest {

	@Mock
	private SpotifyClient spotifyClient;

	@Mock
	private MusicProviderLinkRepository linkRepository;

	private TokenEncryptor tokenEncryptor;
	private SpotifyProvider spotifyProvider;

	@BeforeEach
	void setUp() {
		NowPlayingProperties properties = new NowPlayingProperties();
		properties.setTokenEncryptionKey(
				Base64.getEncoder().encodeToString("dev-only-token-encryption-key-32".getBytes()));
		properties.getSpotify().setClientId("client-id");
		properties.getSpotify().setRedirectUri("http://localhost/callback");
		tokenEncryptor = new TokenEncryptor(properties);
		spotifyProvider = new SpotifyProvider(spotifyClient, linkRepository, tokenEncryptor, properties);
	}

	private MusicProviderLink linkWithTokens(UUID userId, String accessToken, String refreshToken,
			Instant accessExpiresAt) {
		return new MusicProviderLink(UUID.randomUUID(), userId, SpotifyProvider.NAME,
				tokenEncryptor.encrypt(accessToken), tokenEncryptor.encrypt(refreshToken), accessExpiresAt,
				Instant.now());
	}

	@Test
	void buildAuthorizeUrl_containsClientIdScopeRedirectAndState() {
		String url = spotifyProvider.buildAuthorizeUrl("my-state");

		assertThat(url).contains("client_id=client-id").contains("scope=user-read-currently-playing")
				.contains("state=my-state").contains("response_type=code");
	}

	@Test
	void connect_savesNewLinkWithEncryptedTokens() {
		UUID userId = UUID.randomUUID();
		when(spotifyClient.exchangeCode("auth-code"))
				.thenReturn(new SpotifyClient.TokenResponse("access", "refresh", 3600));
		when(linkRepository.findByUserIdAndProvider(userId, SpotifyProvider.NAME)).thenReturn(Optional.empty());

		spotifyProvider.connect(userId, "auth-code");

		verify(linkRepository).save(any(MusicProviderLink.class));
	}

	@Test
	void connect_updatesExistingLink_whenAlreadyConnected() {
		UUID userId = UUID.randomUUID();
		MusicProviderLink existing = linkWithTokens(userId, "old-access", "old-refresh", Instant.now());
		when(spotifyClient.exchangeCode("auth-code"))
				.thenReturn(new SpotifyClient.TokenResponse("new-access", "new-refresh", 3600));
		when(linkRepository.findByUserIdAndProvider(userId, SpotifyProvider.NAME)).thenReturn(Optional.of(existing));

		spotifyProvider.connect(userId, "auth-code");

		verify(linkRepository, never()).save(any());
		assertThat(tokenEncryptor.decrypt(existing.getAccessTokenEncrypted())).isEqualTo("new-access");
	}

	@Test
	void getCurrentlyPlaying_throws_whenNotConnected() {
		UUID userId = UUID.randomUUID();
		when(linkRepository.findByUserIdAndProvider(userId, SpotifyProvider.NAME)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> spotifyProvider.getCurrentlyPlaying(userId))
				.isInstanceOf(ProviderNotConnectedException.class);
	}

	@Test
	void getCurrentlyPlaying_returnsEmpty_whenNothingPlaying() {
		UUID userId = UUID.randomUUID();
		MusicProviderLink link = linkWithTokens(userId, "access", "refresh", Instant.now().plusSeconds(3600));
		when(linkRepository.findByUserIdAndProvider(userId, SpotifyProvider.NAME)).thenReturn(Optional.of(link));
		when(spotifyClient.getCurrentlyPlaying("access")).thenReturn(null);

		assertThat(spotifyProvider.getCurrentlyPlaying(userId)).isEmpty();
	}

	@Test
	void getCurrentlyPlaying_mapsTrack_whenPlaying() {
		UUID userId = UUID.randomUUID();
		MusicProviderLink link = linkWithTokens(userId, "access", "refresh", Instant.now().plusSeconds(3600));
		when(linkRepository.findByUserIdAndProvider(userId, SpotifyProvider.NAME)).thenReturn(Optional.of(link));
		when(spotifyClient.getCurrentlyPlaying("access")).thenReturn(new SpotifyClient.CurrentlyPlayingResponse(true,
				new SpotifyClient.CurrentlyPlayingResponse.Item("Paranoid",
						List.of(new SpotifyClient.CurrentlyPlayingResponse.Artist("Black Sabbath")),
						new SpotifyClient.CurrentlyPlayingResponse.Album("Paranoid",
								List.of(new SpotifyClient.CurrentlyPlayingResponse.Image("http://img"))))));

		Optional<NowPlayingTrack> track = spotifyProvider.getCurrentlyPlaying(userId);

		assertThat(track).hasValue(new NowPlayingTrack("Paranoid", "Black Sabbath", "Paranoid", "http://img", true));
	}

	@Test
	void getCurrentlyPlaying_refreshesToken_whenExpired() {
		UUID userId = UUID.randomUUID();
		MusicProviderLink link = linkWithTokens(userId, "expired-access", "refresh", Instant.now().minusSeconds(60));
		when(linkRepository.findByUserIdAndProvider(userId, SpotifyProvider.NAME)).thenReturn(Optional.of(link));
		when(spotifyClient.refresh("refresh")).thenReturn(new SpotifyClient.TokenResponse("new-access", null, 3600));
		when(spotifyClient.getCurrentlyPlaying("new-access")).thenReturn(null);

		spotifyProvider.getCurrentlyPlaying(userId);

		verify(spotifyClient).getCurrentlyPlaying("new-access");
		assertThat(tokenEncryptor.decrypt(link.getAccessTokenEncrypted())).isEqualTo("new-access");
		assertThat(tokenEncryptor.decrypt(link.getRefreshTokenEncrypted())).isEqualTo("refresh");
	}
}
