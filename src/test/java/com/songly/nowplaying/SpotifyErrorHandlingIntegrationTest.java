package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Zachowanie przy błędach Spotify na pełnej ścieżce: kontroler → serwis →
 * provider → HTTP → baza. Podstawiamy prawdziwy serwer HTTP zamiast mocka
 * providera, bo sprawdzamy między innymi to, czego mock repozytorium nie
 * udowodni — że skasowanie martwego linku faktycznie <b>commituje się</b> mimo
 * wyjątku lecącego z metody transakcyjnej.
 *
 * <p>
 * Przy okazji jest to jedyne miejsce, które przechodzi cały udany callback
 * OAuth (state → wymiana kodu → zapis zaszyfrowanych tokenów).
 */
@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false", "songly.now-playing.poll.enabled=false",
		"songly.now-playing.stream.heartbeat.enabled=false"})
class SpotifyErrorHandlingIntegrationTest extends AbstractIntegrationTest {

	private static final HttpServer SPOTIFY = fakeSpotify();

	/**
	 * Domyślne {@code expires_in=0} sprawia, że kolejne wywołanie od razu idzie
	 * ścieżką odświeżania tokenu — to ta ścieżka nas tu interesuje.
	 */
	private static final Responder FRESHLY_EXPIRING_TOKEN = json(200, """
			{"access_token":"access","refresh_token":"refresh","expires_in":0}""");

	private static volatile Responder tokenResponder = FRESHLY_EXPIRING_TOKEN;

	private static volatile Responder apiResponder = json(200, "{}");

	@FunctionalInterface
	private interface Responder {
		void respond(HttpExchange exchange) throws IOException;
	}

	private static HttpServer fakeSpotify() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/api/token", exchange -> {
				exchange.getRequestBody().readAllBytes();
				tokenResponder.respond(exchange);
				exchange.close();
			});
			server.createContext("/v1", exchange -> {
				apiResponder.respond(exchange);
				exchange.close();
			});
			server.start();
			return server;
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String baseUrl() {
		return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + SPOTIFY.getAddress().getPort();
	}

	@DynamicPropertySource
	static void spotifyEndpoints(DynamicPropertyRegistry registry) {
		registry.add("songly.now-playing.spotify.token-url", () -> baseUrl() + "/api/token");
		registry.add("songly.now-playing.spotify.api-base-url", () -> baseUrl() + "/v1");
	}

	private static Responder json(int status, String body) {
		return exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		};
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MusicProviderLinkRepository linkRepository;

	/**
	 * Fałszywy Spotify jest statyczny (jeden na klasę), więc bez resetu odpowiedź
	 * ustawiona przez jeden test psułaby fazę przygotowania kolejnego — a kolejność
	 * metod w JUnit nie jest gwarantowana.
	 */
	@BeforeEach
	void resetFakeSpotify() {
		tokenResponder = FRESHLY_EXPIRING_TOKEN;
		apiResponder = json(200, "{}");
	}

	private record ConnectedUser(String accessToken, UUID userId) {
	}

	/** Rejestracja + pełne przejście OAuth przez publiczny callback. */
	private ConnectedUser connectSpotify(String email) throws Exception {
		MvcResult registration = registerAndVerify(email, "password123");
		String accessToken = objectMapper.readTree(registration.getResponse().getContentAsString()).get("accessToken")
				.asText();

		MvcResult profile = mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		UUID userId = UUID
				.fromString(objectMapper.readTree(profile.getResponse().getContentAsString()).get("userId").asText());

		MvcResult connect = mockMvc
				.perform(post("/api/v1/now-playing/spotify/connect").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		String authorizeUrl = objectMapper.readTree(connect.getResponse().getContentAsString()).get("authorizeUrl")
				.asText();
		String state = UriComponentsBuilder.fromUriString(authorizeUrl).build().getQueryParams().getFirst("state");

		mockMvc.perform(get("/api/v1/now-playing/spotify/callback").param("code", "auth-code").param("state", state))
				.andExpect(status().isNoContent());
		assertThat(linkRepository.findByUserIdAndProvider(userId, SpotifyProvider.NAME)).isPresent();
		return new ConnectedUser(accessToken, userId);
	}

	@Test
	void revokedConsent_returnsConflictAndDropsTheDeadLink() throws Exception {
		ConnectedUser user = connectSpotify("spotify-revoked@songly.io");
		tokenResponder = json(400, """
				{"error":"invalid_grant","error_description":"Refresh token revoked"}""");

		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + user.accessToken()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PROVIDER_REAUTHORIZATION_REQUIRED"));

		// Sedno: gdyby wyjątek wycofał transakcję, link zostałby w bazie i poller
		// dobijałby się do Spotify z tym samym martwym tokenem w kółko.
		assertThat(linkRepository.findByUserIdAndProvider(user.userId(), SpotifyProvider.NAME)).isEmpty();
	}

	@Test
	void providerFailure_surfacesAsBadGateway_notAsOurInternalError() throws Exception {
		tokenResponder = json(200, """
				{"access_token":"access","refresh_token":"refresh","expires_in":3600}""");
		ConnectedUser user = connectSpotify("spotify-outage@songly.io");
		apiResponder = json(503, "{}");

		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + user.accessToken()))
				.andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("MUSIC_PROVIDER_ERROR"));

		assertThat(linkRepository.findByUserIdAndProvider(user.userId(), SpotifyProvider.NAME)).isPresent();
	}

	@Test
	void staleAuthorizationCode_returnsBadRequest() throws Exception {
		MvcResult registration = registerAndVerify("spotify-stale-code@songly.io", "password123");
		String accessToken = objectMapper.readTree(registration.getResponse().getContentAsString()).get("accessToken")
				.asText();
		MvcResult connect = mockMvc
				.perform(post("/api/v1/now-playing/spotify/connect").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		String authorizeUrl = objectMapper.readTree(connect.getResponse().getContentAsString()).get("authorizeUrl")
				.asText();
		String state = UriComponentsBuilder.fromUriString(authorizeUrl).build().getQueryParams().getFirst("state");
		tokenResponder = json(400, """
				{"error":"invalid_grant","error_description":"Invalid authorization code"}""");

		mockMvc.perform(get("/api/v1/now-playing/spotify/callback").param("code", "stale").param("state", state))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUTHORIZATION_CODE"));
	}
}
