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
		SpotifyClient.TokenResponse tokens = exchangeCode(authorizationCode);
		Instant now = Instant.now();
		Instant expiresAt = now.plusSeconds(tokens.expiresInSeconds());
		String accessEncrypted = tokenEncryptor.encrypt(tokens.accessToken());
		String refreshEncrypted = tokenEncryptor.encrypt(tokens.refreshToken());
		linkRepository.findByUserIdAndProvider(userId, NAME).ifPresentOrElse(
				link -> link.updateTokens(accessEncrypted, refreshEncrypted, expiresAt),
				() -> linkRepository.save(new MusicProviderLink(UUID.randomUUID(), userId, NAME, accessEncrypted,
						refreshEncrypted, expiresAt, now)));
	}

	/**
	 * {@code noRollbackFor} nie jest ozdobnikiem: przy odwołanej zgodzie
	 * {@link #freshAccessToken} kasuje martwy link i dopiero potem rzuca. Bez tego
	 * wyjątku rollback cofnąłby to kasowanie i poller próbowałby tego samego
	 * użytkownika co 30 sekund w nieskończoność.
	 *
	 * <p>
	 * {@link SpotifyApiException} z tego samego powodu od drugiej strony (ADR
	 * 0049): token odświeżony tuż przed nieudanym wywołaniem API (429, 5xx) jest
	 * prawdziwy i ma przetrwać. Rollback go wyrzucał, więc każda próba podczas
	 * awarii Spotify odświeżała od nowa, a gdy Spotify zwrócił nowy refresh token,
	 * w bazie zostawał stary — następne odświeżenie mogło dostać
	 * {@code invalid_grant} i skasować zdrowy link.
	 */
	@Override
	@Transactional(noRollbackFor = {ProviderReauthorizationRequiredException.class, SpotifyApiException.class})
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

	private SpotifyClient.TokenResponse exchangeCode(String authorizationCode) {
		try {
			return spotifyClient.exchangeCode(authorizationCode);
		} catch (SpotifyApiException ex) {
			// Tu invalid_grant znaczy "zły kod z redirectu", a nie "cofnięta zgoda" —
			// nie ma jeszcze żadnego linku, który mógłby umrzeć.
			if (ex.isInvalidGrant()) {
				throw new InvalidAuthorizationCodeException();
			}
			throw ex;
		}
	}

	private String freshAccessToken(MusicProviderLink link) {
		if (!link.isAccessTokenExpired(Instant.now())) {
			return tokenEncryptor.decrypt(link.getAccessTokenEncrypted());
		}
		String refreshToken = tokenEncryptor.decrypt(link.getRefreshTokenEncrypted());
		SpotifyClient.TokenResponse tokens = refresh(link, refreshToken);
		// Spotify przy refresh_token grant zwykle nie zwraca nowego refresh tokenu
		String newRefreshToken = tokens.refreshToken() != null ? tokens.refreshToken() : refreshToken;
		link.updateTokens(tokenEncryptor.encrypt(tokens.accessToken()), tokenEncryptor.encrypt(newRefreshToken),
				Instant.now().plusSeconds(tokens.expiresInSeconds()));
		return tokens.accessToken();
	}

	/**
	 * {@code invalid_grant} na refreshu jest nieodwracalny — użytkownik cofnął
	 * zgodę w panelu Spotify i ten refresh token już nigdy nie zadziała. Trzymanie
	 * takiego linku nic nie daje, a kosztuje jedno nieudane wywołanie na każdy
	 * przebieg polla, na zawsze. Dlatego kasujemy go tutaj, przy pierwszym
	 * dowodzie.
	 *
	 * <p>
	 * Cache statusu zostawiamy — wygasa sam po {@code cache-ttl}, a znajomym
	 * "przestał słuchać" ogłosi zamiatanie wygasłych statusów w pollerze.
	 */
	private SpotifyClient.TokenResponse refresh(MusicProviderLink link, String refreshToken) {
		try {
			return spotifyClient.refresh(refreshToken);
		} catch (SpotifyApiException ex) {
			if (ex.isInvalidGrant()) {
				linkRepository.delete(link);
				throw new ProviderReauthorizationRequiredException();
			}
			throw ex;
		}
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
