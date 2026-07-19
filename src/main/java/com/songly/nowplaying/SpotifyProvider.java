package com.songly.nowplaying;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class SpotifyProvider implements MusicProvider {

	static final String NAME = "spotify";

	private final SpotifyClient spotifyClient;
	private final MusicProviderLinkRepository linkRepository;
	private final TokenEncryptor tokenEncryptor;
	private final NowPlayingProperties properties;

	SpotifyProvider(SpotifyClient spotifyClient, MusicProviderLinkRepository linkRepository,
			TokenEncryptor tokenEncryptor, NowPlayingProperties properties) {
		this.spotifyClient = spotifyClient;
		this.linkRepository = linkRepository;
		this.tokenEncryptor = tokenEncryptor;
		this.properties = properties;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String buildAuthorizeUrl(String state) {
		NowPlayingProperties.Spotify spotify = properties.getSpotify();
		return UriComponentsBuilder.fromUriString(spotify.getAuthorizeUrl()).queryParam("response_type", "code")
				.queryParam("client_id", spotify.getClientId()).queryParam("scope", "user-read-currently-playing")
				.queryParam("redirect_uri", spotify.getRedirectUri()).queryParam("state", state).build().toUriString();
	}

	@Override
	@Transactional
	public void connect(UUID userId, String authorizationCode) {
		SpotifyClient.TokenResponse tokens = spotifyClient.exchangeCode(authorizationCode);
		Instant now = Instant.now();
		Instant expiresAt = now.plusSeconds(tokens.expiresInSeconds());
		String accessEncrypted = tokenEncryptor.encrypt(tokens.accessToken());
		String refreshEncrypted = tokenEncryptor.encrypt(tokens.refreshToken());
		linkRepository.findByUserIdAndProvider(userId, NAME).ifPresentOrElse(
				link -> link.updateTokens(accessEncrypted, refreshEncrypted, expiresAt),
				() -> linkRepository.save(new MusicProviderLink(UUID.randomUUID(), userId, NAME, accessEncrypted,
						refreshEncrypted, expiresAt, now)));
	}

	@Override
	@Transactional
	public Optional<NowPlayingTrack> getCurrentlyPlaying(UUID userId) {
		MusicProviderLink link = linkRepository.findByUserIdAndProvider(userId, NAME)
				.orElseThrow(ProviderNotConnectedException::new);
		String accessToken = freshAccessToken(link);
		SpotifyClient.CurrentlyPlayingResponse response = spotifyClient.getCurrentlyPlaying(accessToken);
		if (response == null || response.item() == null) {
			return Optional.empty();
		}
		return Optional.of(toTrack(response));
	}

	private String freshAccessToken(MusicProviderLink link) {
		if (!link.isAccessTokenExpired(Instant.now())) {
			return tokenEncryptor.decrypt(link.getAccessTokenEncrypted());
		}
		String refreshToken = tokenEncryptor.decrypt(link.getRefreshTokenEncrypted());
		SpotifyClient.TokenResponse tokens = spotifyClient.refresh(refreshToken);
		// Spotify przy refresh_token grant zwykle nie zwraca nowego refresh tokenu
		String newRefreshToken = tokens.refreshToken() != null ? tokens.refreshToken() : refreshToken;
		link.updateTokens(tokenEncryptor.encrypt(tokens.accessToken()), tokenEncryptor.encrypt(newRefreshToken),
				Instant.now().plusSeconds(tokens.expiresInSeconds()));
		return tokens.accessToken();
	}

	private NowPlayingTrack toTrack(SpotifyClient.CurrentlyPlayingResponse response) {
		SpotifyClient.CurrentlyPlayingResponse.Item item = response.item();
		String artist = item.artists() == null
				? null
				: String.join(", ",
						item.artists().stream().map(SpotifyClient.CurrentlyPlayingResponse.Artist::name).toList());
		String album = item.album() != null ? item.album().name() : null;
		String imageUrl = item.album() != null ? firstImageUrl(item.album().images()) : null;
		return new NowPlayingTrack(item.name(), artist, album, imageUrl, response.isPlaying());
	}

	private String firstImageUrl(List<SpotifyClient.CurrentlyPlayingResponse.Image> images) {
		return images == null || images.isEmpty() ? null : images.get(0).url();
	}
}
