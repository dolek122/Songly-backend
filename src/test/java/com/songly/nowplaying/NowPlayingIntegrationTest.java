package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

// Poll wyłączony: testowy strumień SSE zostaje w rejestrze do timeoutu, więc
// harmonogram odpytywałby providera w tle w trakcie asercji (ADR 0007, Etap 1b).
// Heartbeat też wyłączony: leci w te same emittery co realne zdarzenia, więc
// testy liczące pushe łapałyby jego zamiast tego, co sprawdzają.
@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false", "songly.now-playing.poll.enabled=false",
		"songly.now-playing.stream.heartbeat.enabled=false"})
class NowPlayingIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private NowPlayingStreamHub streamHub;

	private String registerAndGetAccessToken(String email) throws Exception {
		MvcResult result = registerAndVerify(email, "password123");
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
	}

	private record TestUser(String accessToken, String userId) {
	}

	private TestUser registerUser(String email) throws Exception {
		String accessToken = registerAndGetAccessToken(email);
		MvcResult profile = mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		String userId = objectMapper.readTree(profile.getResponse().getContentAsString()).get("userId").asText();
		return new TestUser(accessToken, userId);
	}

	private void befriend(TestUser a, TestUser b) throws Exception {
		MvcResult request = mockMvc
				.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + a.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"addresseeId":"%s"}""".formatted(b.userId())))
				.andExpect(status().isCreated()).andReturn();
		String requestId = objectMapper.readTree(request.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + b.accessToken())).andExpect(status().isOk());
	}

	private void setManualStatus(TestUser user, String title, String artist, String source) throws Exception {
		mockMvc.perform(put("/api/v1/now-playing/manual").header("Authorization", "Bearer " + user.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"title":"%s","artist":"%s","source":"%s"}""".formatted(title, artist, source)))
				.andExpect(status().isNoContent());
	}

	@Test
	void connect_returnsSpotifyAuthorizeUrlWithState() throws Exception {
		String accessToken = registerAndGetAccessToken("spotify-connect@songly.io");

		MvcResult result = mockMvc
				.perform(post("/api/v1/now-playing/spotify/connect").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.authorizeUrl").isNotEmpty()).andReturn();

		String url = objectMapper.readTree(result.getResponse().getContentAsString()).get("authorizeUrl").asText();
		assertThat(url).contains("accounts.spotify.com/authorize").contains("state=")
				.contains("scope=user-read-currently-playing");
	}

	private String beginConnect(String accessToken) throws Exception {
		MvcResult connect = mockMvc
				.perform(post("/api/v1/now-playing/spotify/connect").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		String authorizeUrl = objectMapper.readTree(connect.getResponse().getContentAsString()).get("authorizeUrl")
				.asText();
		return UriComponentsBuilder.fromUriString(authorizeUrl).build().getQueryParams().getFirst("state");
	}

	/**
	 * Po drugiej stronie callbacku stoi przeglądarka, nie klient API — błąd ma
	 * wrócić do aplikacji, a nie zostawić użytkownika na stronie z JSON-em.
	 */
	@Test
	void callback_redirectsToAppWithError_whenStateUnknown() throws Exception {
		mockMvc.perform(
				get("/api/v1/now-playing/spotify/callback").param("code", "any-code").param("state", "unknown-state"))
				.andExpect(status().isFound()).andExpect(header().string("Location",
						"songly://now-playing/spotify?result=error&code=INVALID_OAUTH_STATE"));
	}

	@Test
	void callback_redirectsAsDenied_andConsumesState_whenUserRefusesConsent() throws Exception {
		String state = beginConnect(registerAndGetAccessToken("spotify-denied@songly.io"));

		mockMvc.perform(
				get("/api/v1/now-playing/spotify/callback").param("error", "access_denied").param("state", state))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", "songly://now-playing/spotify?result=denied"));

		// Odmowa zużywa state — podstawienie go potem z kodem nie może już
		// dokończyć połączenia.
		mockMvc.perform(get("/api/v1/now-playing/spotify/callback").param("code", "late-code").param("state", state))
				.andExpect(header().string("Location",
						"songly://now-playing/spotify?result=error&code=INVALID_OAUTH_STATE"));
	}

	@Test
	void callback_redirectsAsProviderError_whenProviderReturnsOtherError() throws Exception {
		String state = beginConnect(registerAndGetAccessToken("spotify-provider-error@songly.io"));

		mockMvc.perform(
				get("/api/v1/now-playing/spotify/callback").param("error", "server_error").param("state", state))
				.andExpect(status().isFound()).andExpect(header().string("Location",
						"songly://now-playing/spotify?result=error&code=MUSIC_PROVIDER_ERROR"));
	}

	/**
	 * Stan każdego nowego konta — to „nic nie gra", nie błąd. 404 zasłaniało w
	 * kliencie cały ekran, razem z drogami wyjścia z tego stanu.
	 */
	@Test
	void myStatus_returnsNoContent_whenProviderNotConnected() throws Exception {
		String accessToken = registerAndGetAccessToken("spotify-status@songly.io");

		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void disconnect_isIdempotent() throws Exception {
		String accessToken = registerAndGetAccessToken("spotify-disconnect@songly.io");

		mockMvc.perform(delete("/api/v1/now-playing/spotify").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/v1/now-playing/spotify").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void setManualStatus_overridesUnconnectedProvider_andCanBeCleared() throws Exception {
		String accessToken = registerAndGetAccessToken("manual-status@songly.io");

		mockMvc.perform(put("/api/v1/now-playing/manual").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"title":"Bohemian Rhapsody","artist":"Queen","source":"Tidal"}"""))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("Tidal"))
				.andExpect(jsonPath("$.title").value("Bohemian Rhapsody"))
				.andExpect(jsonPath("$.artist").value("Queen")).andExpect(jsonPath("$.playing").value(true));

		mockMvc.perform(delete("/api/v1/now-playing/manual").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void setManualStatus_defaultsSourceLabel_whenNotProvided() throws Exception {
		String accessToken = registerAndGetAccessToken("manual-status-default-source@songly.io");

		mockMvc.perform(put("/api/v1/now-playing/manual").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"title":"Nowa piosenka"}""")).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("manual"));
	}

	@Test
	void setManualStatus_rejectsBlankTitle() throws Exception {
		String accessToken = registerAndGetAccessToken("manual-status-invalid@songly.io");

		mockMvc.perform(put("/api/v1/now-playing/manual").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"title":""}""")).andExpect(status().isBadRequest());
	}

	@Test
	void friends_returnsOnlyFriendsWithCachedStatus_withProfileData() throws Exception {
		TestUser viewer = registerUser("np-friends-viewer@songly.io");
		TestUser playing = registerUser("np-friends-playing@songly.io");
		TestUser silent = registerUser("np-friends-silent@songly.io");
		TestUser stranger = registerUser("np-friends-stranger@songly.io");

		setManualStatus(playing, "Bohemian Rhapsody", "Queen", "Tidal");
		setManualStatus(stranger, "Come As You Are", "Nirvana", "Tidal");

		befriend(viewer, playing);
		befriend(viewer, silent); // znajomy bez statusu — pomijany
		// stranger celowo nie jest znajomym — nie powinien się pojawić

		mockMvc.perform(get("/api/v1/now-playing/friends").header("Authorization", "Bearer " + viewer.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].userId").value(playing.userId()))
				.andExpect(jsonPath("$[0].displayName").value("np-friends-playing"))
				.andExpect(jsonPath("$[0].status.provider").value("Tidal"))
				.andExpect(jsonPath("$[0].status.title").value("Bohemian Rhapsody"))
				.andExpect(jsonPath("$[0].status.artist").value("Queen"))
				.andExpect(jsonPath("$[0].status.playing").value(true));
	}

	/**
	 * Status zawieszonego konta nie opuszcza serwera (ADR 0049). ADR 0026 zakładał,
	 * że zawieszony go „nie odświeży”, ale ręczny status żyje godzinami, a ten ze
	 * Spotify odświeża serwerowy poll.
	 */
	@Test
	void friends_leavesOutSuspendedFriend() throws Exception {
		TestUser viewer = registerUser("np-friends-suspended-viewer@songly.io");
		TestUser suspended = registerUser("np-friends-suspended@songly.io");
		setManualStatus(suspended, "Bohemian Rhapsody", "Queen", "Tidal");
		befriend(viewer, suspended);
		jdbcTemplate.update("UPDATE users SET suspended_at = now(), suspension_reason = 'test' WHERE id = ?::uuid",
				suspended.userId());

		mockMvc.perform(get("/api/v1/now-playing/friends").header("Authorization", "Bearer " + viewer.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void friends_returnsEmpty_whenNoFriendHasStatus() throws Exception {
		TestUser viewer = registerUser("np-friends-empty-viewer@songly.io");
		TestUser friend = registerUser("np-friends-empty-friend@songly.io");
		befriend(viewer, friend);

		mockMvc.perform(get("/api/v1/now-playing/friends").header("Authorization", "Bearer " + viewer.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void stream_opensEventStream_forAuthenticatedUser() throws Exception {
		TestUser viewer = registerUser("np-stream@songly.io");

		mockMvc.perform(get("/api/v1/now-playing/stream").header("Authorization", "Bearer " + viewer.accessToken()))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/event-stream")));
	}

	/**
	 * Etap 2 end-to-end: zmiana statusu wychodzi na kanał Redis, wraca do tej samej
	 * instancji przez {@code RedisMessageListenerContainer} i ląduje w strumieniu
	 * SSE znajomego. Ta sama ścieżka niesie zmianę między instancjami — tam, gdzie
	 * tu jest jeden proces, w produkcji stoi drugi węzeł.
	 */
	@Test
	void statusChange_reachesFriendStream_throughRedisChannel() throws Exception {
		TestUser viewer = registerUser("np-fanout-viewer@songly.io");
		TestUser subject = registerUser("np-fanout-subject@songly.io");
		TestUser stranger = registerUser("np-fanout-stranger@songly.io");
		befriend(viewer, subject);

		CountDownLatch delivered = new CountDownLatch(1);
		List<Object> received = new CopyOnWriteArrayList<>();
		streamHub.register(UUID.fromString(viewer.userId()), new SseEmitter() {
			@Override
			public void send(SseEventBuilder builder) {
				received.add(builder);
				delivered.countDown();
			}
		});

		// Obcy nie ma prawa trafić do cudzego strumienia — publikujemy go pierwszego,
		// żeby jego wiadomość zdążyła przejść przez kanał przed asercją.
		setManualStatus(stranger, "Come As You Are", "Nirvana", "Tidal");
		setManualStatus(subject, "Bohemian Rhapsody", "Queen", "Tidal");

		assertThat(delivered.await(10, TimeUnit.SECONDS)).as("push SSE po zmianie statusu znajomego").isTrue();
		assertThat(received).hasSize(1);
	}

	@Test
	void settings_defaultToVisible_andCanBeTurnedOff() throws Exception {
		TestUser user = registerUser("np-settings@songly.io");

		mockMvc.perform(get("/api/v1/now-playing/settings").header("Authorization", "Bearer " + user.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shareWithFriends").value(true));

		mockMvc.perform(put("/api/v1/now-playing/settings").header("Authorization", "Bearer " + user.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"shareWithFriends":false}""")).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/now-playing/settings").header("Authorization", "Bearer " + user.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shareWithFriends").value(false));
	}

	@Test
	void settings_rejectMissingFlag() throws Exception {
		TestUser user = registerUser("np-settings-invalid@songly.io");

		mockMvc.perform(put("/api/v1/now-playing/settings").header("Authorization", "Bearer " + user.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
	}

	/**
	 * Wyłączona widoczność chowa status przed znajomymi (ADR 0007, Etap 4), ale
	 * właściciel nadal widzi swój własny w {@code /me}.
	 */
	@Test
	void hiddenStatus_disappearsFromFriendsList_butStaysVisibleToOwner() throws Exception {
		TestUser viewer = registerUser("np-hidden-viewer@songly.io");
		TestUser hidden = registerUser("np-hidden-subject@songly.io");
		befriend(viewer, hidden);
		setManualStatus(hidden, "Bohemian Rhapsody", "Queen", "Tidal");

		mockMvc.perform(get("/api/v1/now-playing/friends").header("Authorization", "Bearer " + viewer.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

		mockMvc.perform(put("/api/v1/now-playing/settings").header("Authorization", "Bearer " + hidden.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"shareWithFriends":false}""")).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/now-playing/friends").header("Authorization", "Bearer " + viewer.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + hidden.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Bohemian Rhapsody"));
	}

	@Test
	void hiddenStatus_doesNotReachFriendStream() throws Exception {
		TestUser viewer = registerUser("np-hidden-stream-viewer@songly.io");
		TestUser hidden = registerUser("np-hidden-stream-subject@songly.io");
		TestUser visible = registerUser("np-hidden-stream-visible@songly.io");
		befriend(viewer, hidden);
		befriend(viewer, visible);
		mockMvc.perform(put("/api/v1/now-playing/settings").header("Authorization", "Bearer " + hidden.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"shareWithFriends":false}""")).andExpect(status().isNoContent());

		CountDownLatch delivered = new CountDownLatch(1);
		List<Object> received = new CopyOnWriteArrayList<>();
		streamHub.register(UUID.fromString(viewer.userId()), new SseEmitter() {
			@Override
			public void send(SseEventBuilder builder) {
				received.add(builder);
				delivered.countDown();
			}
		});

		setManualStatus(hidden, "Come As You Are", "Nirvana", "Tidal");
		// Status widocznego znajomego jest sondą: gdy on dojdzie, wiadomość ukrytego
		// też zdążyła już przejść przez kanał — i skoro jej nie ma, to jej nie będzie.
		setManualStatus(visible, "Bohemian Rhapsody", "Queen", "Tidal");

		assertThat(delivered.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(received).hasSize(1);
	}

	/**
	 * Wyłączenie widoczności musi dojść do znajomego, który <b>już</b> ma otwarty
	 * strumień — dla niego nie ma żadnego kolejnego pushu, więc bez tego zdarzenia
	 * widziałby ostatni status w nieskończoność (Claude.md §9).
	 */
	@Test
	void turningSharingOff_reachesOpenFriendStream() throws Exception {
		TestUser viewer = registerUser("np-optout-viewer@songly.io");
		TestUser subject = registerUser("np-optout-subject@songly.io");
		befriend(viewer, subject);
		setManualStatus(subject, "Bohemian Rhapsody", "Queen", "Tidal");

		CountDownLatch stopped = new CountDownLatch(1);
		List<Object> received = new CopyOnWriteArrayList<>();
		streamHub.register(UUID.fromString(viewer.userId()), new SseEmitter() {
			@Override
			public void send(SseEventBuilder builder) {
				received.add(builder);
				stopped.countDown();
			}
		});

		mockMvc.perform(put("/api/v1/now-playing/settings").header("Authorization", "Bearer " + subject.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"shareWithFriends":false}""")).andExpect(status().isNoContent());

		assertThat(stopped.await(10, TimeUnit.SECONDS)).as("push \"przestał słuchać\" po ukryciu statusu").isTrue();
	}

	@Test
	void stream_isRejected_aboveLimitPerUser() throws Exception {
		TestUser user = registerUser("np-stream-limit@songly.io");
		UUID userId = UUID.fromString(user.userId());
		for (int i = 0; i < 3; i++) {
			streamHub.register(userId, new SseEmitter());
		}

		mockMvc.perform(get("/api/v1/now-playing/stream").header("Authorization", "Bearer " + user.accessToken()))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_STREAMS"));
	}

	@Test
	void nowPlayingEndpoints_requireAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/now-playing/spotify/connect")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/now-playing/me")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/now-playing/friends")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/now-playing/stream")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/v1/now-playing/manual")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/now-playing/settings")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/v1/now-playing/settings")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/v1/now-playing/manual")).andExpect(status().isUnauthorized());
	}
}
