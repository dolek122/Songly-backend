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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class NowPlayingIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String registerAndGetAccessToken(String email) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isCreated())
				.andReturn();
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

	@Test
	void callback_returnsBadRequest_whenStateUnknown() throws Exception {
		mockMvc.perform(
				get("/api/v1/now-playing/spotify/callback").param("code", "any-code").param("state", "unknown-state"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OAUTH_STATE"));
	}

	@Test
	void myStatus_returnsNotFound_whenProviderNotConnected() throws Exception {
		String accessToken = registerAndGetAccessToken("spotify-status@songly.io");

		mockMvc.perform(get("/api/v1/now-playing/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROVIDER_NOT_CONNECTED"));
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
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROVIDER_NOT_CONNECTED"));
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

	@Test
	void nowPlayingEndpoints_requireAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/now-playing/spotify/connect")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/now-playing/me")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/now-playing/friends")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/now-playing/stream")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/v1/now-playing/manual")).andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/v1/now-playing/manual")).andExpect(status().isUnauthorized());
	}
}
