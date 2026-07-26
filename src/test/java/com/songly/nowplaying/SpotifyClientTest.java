package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * Testujemy przez prawdziwy serwer HTTP na loopbacku, a nie przez zamockowany
 * {@code RestClient}: połowa tego, co ta klasa robi, dzieje się na drucie —
 * Basic auth, kodowanie formularza, 204 bez ciała, nagłówek
 * {@code Retry-After}. Mock po stronie Springa przyklepałby nasze własne
 * założenia zamiast je sprawdzić.
 */
class SpotifyClientTest {

	private HttpServer server;
	private SpotifyClient spotifyClient;
	private Responder responder = exchange -> noContent(exchange);

	private String requestPath;
	private String requestBody;
	private String authorizationHeader;

	@FunctionalInterface
	private interface Responder {
		void respond(HttpExchange exchange) throws IOException;
	}

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			requestPath = exchange.getRequestURI().getPath();
			requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			authorizationHeader = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
			responder.respond(exchange);
			exchange.close();
		});
		server.start();

		String baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
				+ server.getAddress().getPort();
		NowPlayingProperties properties = new NowPlayingProperties();
		properties.getSpotify().setClientId("client-id");
		properties.getSpotify().setClientSecret("client-secret");
		properties.getSpotify().setRedirectUri("http://localhost/callback");
		properties.getSpotify().setTokenUrl(baseUrl + "/api/token");
		properties.getSpotify().setApiBaseUrl(baseUrl + "/v1");
		spotifyClient = new SpotifyClient(properties, new ObjectMapper());
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private static void noContent(HttpExchange exchange) throws IOException {
		exchange.sendResponseHeaders(HttpStatus.NO_CONTENT.value(), -1);
	}

	private Responder json(int status, String body) {
		return exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		};
	}

	@Test
	void exchangeCode_sendsBasicAuthAndFormEncodedGrant() {
		responder = json(200, """
				{"access_token":"acc","refresh_token":"ref","expires_in":3600}""");

		SpotifyClient.TokenResponse tokens = spotifyClient.exchangeCode("the-code");

		assertThat(tokens.accessToken()).isEqualTo("acc");
		assertThat(tokens.refreshToken()).isEqualTo("ref");
		assertThat(tokens.expiresInSeconds()).isEqualTo(3600);
		assertThat(requestPath).isEqualTo("/api/token");
		assertThat(authorizationHeader).isEqualTo("Basic "
				+ Base64.getEncoder().encodeToString("client-id:client-secret".getBytes(StandardCharsets.UTF_8)));
		assertThat(requestBody).contains("grant_type=authorization_code").contains("code=the-code")
				.contains("redirect_uri=http%3A%2F%2Flocalhost%2Fcallback");
	}

	@Test
	void refresh_sendsRefreshTokenGrant() {
		responder = json(200, """
				{"access_token":"fresh","expires_in":3600}""");

		SpotifyClient.TokenResponse tokens = spotifyClient.refresh("old-refresh");

		// Spotify przy tym grancie zwykle nie odsyła refresh tokenu — null jest
		// normalną odpowiedzią, nie błędem parsowania.
		assertThat(tokens.accessToken()).isEqualTo("fresh");
		assertThat(tokens.refreshToken()).isNull();
		assertThat(requestBody).contains("grant_type=refresh_token").contains("refresh_token=old-refresh");
	}

	@Test
	void refresh_reportsInvalidGrant_whenUserRevokedConsent() {
		responder = json(400, """
				{"error":"invalid_grant","error_description":"Refresh token revoked"}""");

		assertThatThrownBy(() -> spotifyClient.refresh("dead-refresh"))
				.isInstanceOfSatisfying(SpotifyApiException.class, ex -> assertThat(ex.isInvalidGrant()).isTrue());
	}

	/**
	 * Bramka albo proxy potrafi odpowiedzieć HTML-em zamiast JSON-em OAuth. To nie
	 * może przesłonić samego błędu HTTP ani — tym bardziej — udawać odwołanej
	 * zgody, bo skasowalibyśmy komuś działający link do Spotify.
	 */
	@Test
	void refresh_keepsHttpStatus_whenErrorBodyIsNotJson() {
		responder = exchange -> {
			byte[] bytes = "<html>Bad Request</html>".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/html");
			exchange.sendResponseHeaders(400, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		};

		assertThatThrownBy(() -> spotifyClient.refresh("some-refresh"))
				.isInstanceOfSatisfying(SpotifyApiException.class, ex -> {
					assertThat(ex.isInvalidGrant()).isFalse();
					assertThat(ex.upstreamStatus()).isEqualTo(400);
				});
	}

	@Test
	void getCurrentlyPlaying_returnsNull_whenSpotifyAnswers204() {
		responder = SpotifyClientTest::noContent;

		assertThat(spotifyClient.getCurrentlyPlaying("token")).isNull();
		assertThat(requestPath).isEqualTo("/v1/me/player/currently-playing");
		assertThat(authorizationHeader).isEqualTo("Bearer token");
	}

	@Test
	void getCurrentlyPlaying_mapsTrackPayload() {
		responder = json(200, """
				{"is_playing":true,"item":{"name":"Paranoid","artists":[{"name":"Black Sabbath"}],
				"album":{"name":"Paranoid","images":[{"url":"http://img"}]}}}""");

		SpotifyClient.CurrentlyPlayingResponse response = spotifyClient.getCurrentlyPlaying("token");

		assertThat(response.isPlaying()).isTrue();
		assertThat(response.item().name()).isEqualTo("Paranoid");
		assertThat(response.item().artists()).singleElement()
				.extracting(SpotifyClient.CurrentlyPlayingResponse.Artist::name).isEqualTo("Black Sabbath");
		assertThat(response.item().album().images().get(0).url()).isEqualTo("http://img");
	}

	@Test
	void getCurrentlyPlaying_mapsClientErrorToBadGateway() {
		responder = json(401, """
				{"error":{"status":401,"message":"Invalid access token"}}""");

		assertThatThrownBy(() -> spotifyClient.getCurrentlyPlaying("token"))
				.isInstanceOfSatisfying(SpotifyApiException.class, ex -> {
					assertThat(ex.upstreamStatus()).isEqualTo(401);
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
					assertThat(ex.getCode()).isEqualTo("MUSIC_PROVIDER_ERROR");
					assertThat(ex.isRateLimited()).isFalse();
					assertThat(ex.retryAfter()).isNull();
				});
	}

	/**
	 * Wcześniej 5xx nie miało własnej obsługi i wychodziło stąd jako wyjątek
	 * {@code RestClient}, którego nikt wyżej nie rozpoznawał — czyli awaria Spotify
	 * wracała do klienta jako nasze 500.
	 */
	@Test
	void getCurrentlyPlaying_mapsServerErrorToo() {
		responder = json(503, "{}");

		assertThatThrownBy(() -> spotifyClient.getCurrentlyPlaying("token")).isInstanceOfSatisfying(
				SpotifyApiException.class, ex -> assertThat(ex.upstreamStatus()).isEqualTo(503));
	}

	@Test
	void getCurrentlyPlaying_carriesRetryAfter_whenRateLimited() {
		responder = exchange -> {
			exchange.getResponseHeaders().set(HttpHeaders.RETRY_AFTER, "42");
			exchange.sendResponseHeaders(429, -1);
		};

		assertThatThrownBy(() -> spotifyClient.getCurrentlyPlaying("token"))
				.isInstanceOfSatisfying(SpotifyApiException.class, ex -> {
					assertThat(ex.isRateLimited()).isTrue();
					assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(42));
				});
	}

	@Test
	void getCurrentlyPlaying_toleratesUnparsableRetryAfter() {
		responder = exchange -> {
			// Specyfikacja HTTP dopuszcza datę; nie parsujemy jej, ale nie wolno nam się
			// o nią wywrócić — poll ma wtedy sięgnąć po własny domyślny backoff.
			exchange.getResponseHeaders().set(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2026 07:28:00 GMT");
			exchange.sendResponseHeaders(429, -1);
		};

		assertThatThrownBy(() -> spotifyClient.getCurrentlyPlaying("token"))
				.isInstanceOfSatisfying(SpotifyApiException.class, ex -> {
					assertThat(ex.isRateLimited()).isTrue();
					assertThat(ex.retryAfter()).isNull();
				});
	}
}
