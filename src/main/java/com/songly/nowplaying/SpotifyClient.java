package com.songly.nowplaying;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class SpotifyClient {

	private final NowPlayingProperties.Spotify spotify;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	SpotifyClient(NowPlayingProperties properties, ObjectMapper objectMapper) {
		this.spotify = properties.getSpotify();
		this.objectMapper = objectMapper;
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
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw toException(response, tokenErrorCode(response));
				}).body(TokenResponse.class);
	}

	CurrentlyPlayingResponse getCurrentlyPlaying(String accessToken) {
		return restClient.get().uri(spotify.getApiBaseUrl() + "/me/player/currently-playing")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw toException(response, null);
				}).body(CurrentlyPlayingResponse.class);
	}

	private SpotifyApiException toException(ClientHttpResponse response, String error) throws IOException {
		return new SpotifyApiException(response.getStatusCode().value(), error, retryAfter(response));
	}

	private String tokenErrorCode(ClientHttpResponse response) {
		try {
			String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
			return body.isBlank() ? null : objectMapper.readValue(body, TokenErrorResponse.class).error();
		} catch (IOException | JacksonException ex) {
			return null;
		}
	}

	private Duration retryAfter(ClientHttpResponse response) {
		String header = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
		if (header == null) {
			return null;
		}
		try {
			return Duration.ofSeconds(Long.parseLong(header.trim()));
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TokenResponse(@JsonProperty("access_token") String accessToken,
			@JsonProperty("refresh_token") String refreshToken, @JsonProperty("expires_in") long expiresInSeconds) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TokenErrorResponse(String error) {
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
