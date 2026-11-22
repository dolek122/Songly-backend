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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false", "songly.now-playing.poll.enabled=false",
		"songly.now-playing.stream.heartbeat.enabled=false"})
class SpotifyErrorHandlingIntegrationTest extends AbstractIntegrationTest {

	private static final HttpServer SPOTIFY = fakeSpotify();

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

	@Autowired
	private TokenEncryptor tokenEncryptor;

	@BeforeEach
	void resetFakeSpotify() {
		tokenResponder = FRESHLY_EXPIRING_TOKEN;
		apiResponder = json(200, "{}");
	}

	private record ConnectedUser(String accessToken, UUID userId) {
	}

	private ConnectedUser register(String email) throws Exception {
		MvcResult registration = registerAndVerify(email, "password123");
		String accessToken = objectMapper.readTree(registration.getResponse().getContentAsString()).get("accessToken")
				.asText();
		MvcResult profile = mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		UUID userId = UUID
				.fromString(objectMapper.readTree(profile.getResponse().getContentAsString()).get("userId").asText());
		return new ConnectedUser(accessToken, userId);
	}

	private String beginConnect(ConnectedUser user) throws Exception {
		MvcResult connect = mockMvc.perform(
				post("/api/v1/now-playing/spotify/connect").header("Authorization", "Bearer " + user.accessToken()))
				.andExpect(status().isOk()).andReturn();
		String authorizeUrl = objectMapper.readTree(connect.getResponse().getContentAsString()).get("authorizeUrl")
				.asText();
		return UriComponentsBuilder.fromUriString(authorizeUrl).build().getQueryParams().getFirst("state");
	}

	private String callback(String state, String code) throws Exception {
		MvcResult callback = mockMvc
				.perform(get("/api/v1/now-playing/spotify/callback").param("code", code).param("state", state))
				.andExpect(status().isFound()).andReturn();
		String location = callback.getResponse().getHeader("Location");
		assertThat(location).startsWith("songly://now-playing/spotify?result=authorized&ticket=");
		return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("ticket");
	}

	private ResultActions complete(ConnectedUser user, String ticket) throws Exception {
		return mockMvc.perform(post("/api/v1/now-playing/spotify/connect/complete")
				.header("Authorization", "Bearer " + user.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"ticket":"%s"}""".formatted(ticket)));
	}

	private ConnectedUser connectSpotify(String email) throws Exception {
		ConnectedUser user = register(email);
		String ticket = callback(beginConnect(user), "auth-code");

		assertThat(linkRepository.findByUserIdAndProvider(user.userId(), SpotifyProvider.NAME)).isEmpty();
		complete(user, ticket).andExpect(status().isNoContent());
		assertThat(linkRepository.findByUserIdAndProvider(user.userId(), SpotifyProvider.NAME)).isPresent();
		return user;
	}

	@Test
	void linkSentByAnotherAccount_attachesNothing() throws Exception {
		ConnectedUser author = register("spotify-csrf-author@songly.io");
		ConnectedUser victim = register("spotify-csrf-victim@songly.io");
		String authorsState = beginConnect(author);

		String ticket = callback(authorsState, "victims-code");
		complete(victim, ticket).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CONNECT_TICKET"));
		complete(author, authorsState).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CONNECT_TICKET"));
		complete(author, ticket).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CONNECT_TICKET"));

		assertThat(linkRepository.findByUserIdAndProvider(author.userId(), SpotifyProvider.NAME)).isEmpty();
		assertThat(linkRepository.findByUserIdAndProvider(victim.userId(), SpotifyProvider.NAME)).isEmpty();
	}

	@Test
	void ticketIsSingleUse() throws Exception {
		ConnectedUser user = connectSpotify("spotify-ticket-once@songly.io");
		String ticket = callback(beginConnect(user), "second-code");
		complete(user, ticket).andExpect(status().isNoContent());

		complete(user, ticket).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CONNECT_TICKET"));
	}

	@Test
	void blankTicket_isRejectedAsValidationError() throws Exception {
		ConnectedUser user = register("spotify-ticket-blank@songly.io");

		complete(user, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void revokedConsent_returnsConflictAndDropsTheDeadLink() throws Exception {
		ConnectedUser user = connectSpotify("spotify-revoked@songly.io");
		tokenResponder = json(400, """
				{"error":"invalid_grant","error_description":"Refresh token revoked"}""");

		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + user.accessToken()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PROVIDER_REAUTHORIZATION_REQUIRED"));

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
	void tokenRefreshedBeforeProviderFailure_isKept() throws Exception {
		ConnectedUser user = connectSpotify("spotify-refresh-kept@songly.io");
		tokenResponder = json(200, """
				{"access_token":"fresh-access","refresh_token":"rotated-refresh","expires_in":3600}""");
		apiResponder = json(503, "{}");

		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + user.accessToken()))
				.andExpect(status().isBadGateway());

		MusicProviderLink link = linkRepository.findByUserIdAndProvider(user.userId(), SpotifyProvider.NAME)
				.orElseThrow();
		assertThat(tokenEncryptor.decrypt(link.getRefreshTokenEncrypted())).isEqualTo("rotated-refresh");
	}

	@Test
	void staleAuthorizationCode_returnsBadRequest() throws Exception {
		ConnectedUser user = register("spotify-stale-code@songly.io");
		String ticket = callback(beginConnect(user), "stale");
		tokenResponder = json(400, """
				{"error":"invalid_grant","error_description":"Invalid authorization code"}""");

		complete(user, ticket).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_AUTHORIZATION_CODE"));
		assertThat(linkRepository.findByUserIdAndProvider(user.userId(), SpotifyProvider.NAME)).isEmpty();
	}
}
