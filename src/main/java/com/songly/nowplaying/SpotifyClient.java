package com.songly.nowplaying;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Cienka warstwa HTTP do Spotify — bez logiki biznesowej.
 *
 * <p>
 * Timeouty ustawiamy jawnie, bo domyślny klient JDK czeka na odpowiedź w
 * nieskończoność. Te wywołania idą z wątku harmonogramu (ADR 0007, Etap 1b), a
 * zawieszone połączenie do Spotify blokowałoby wtedy poll wszystkich
 * obserwowanych użytkowników — awaria u jednego providera zamieniałaby się w
 * awarię całego presence.
 */
@Component
class SpotifyClient {

	private final NowPlayingProperties.Spotify spotify;
	private final RestClient restClient;

	SpotifyClient(NowPlayingProperties properties) {
		this.spotify = properties.getSpotify();
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(spotify.getConnectTimeout()).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(spotify.getReadTimeout());
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	TokenResponse exchangeCode(String authorizationCode) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("code", authorizationCode);
		form.add("redirect_uri", spotify.getRedirectUri());
		return postToken(form);
	}

	TokenResponse refresh(String refreshToken) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "refresh_token");
		form.add("refresh_token", refreshToken);
		return postToken(form);
	}

	private TokenResponse postToken(MultiValueMap<String, String> form) {
		return restClient.post().uri(spotify.getTokenUrl())
				.headers(headers -> headers.setBasicAuth(spotify.getClientId(), spotify.getClientSecret()))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(TokenResponse.class);
	}

	/** Zwraca null gdy nic nie gra (Spotify odpowiada 204 No Content). */
	CurrentlyPlayingResponse getCurrentlyPlaying(String accessToken) {
		return restClient.get().uri(spotify.getApiBaseUrl() + "/me/player/currently-playing")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).retrieve()
				.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
					throw new SpotifyApiException(response.getStatusCode().value());
				}).body(CurrentlyPlayingResponse.class);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TokenResponse(@JsonProperty("access_token") String accessToken,
			@JsonProperty("refresh_token") String refreshToken, @JsonProperty("expires_in") long expiresInSeconds) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CurrentlyPlayingResponse(@JsonProperty("is_playing") boolean isPlaying, @JsonProperty("item") Item item) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Item(String name, List<Artist> artists, Album album) {
		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Artist(String name) {
		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Album(String name, List<Image> images) {
		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Image(String url) {
		}
	}
}
