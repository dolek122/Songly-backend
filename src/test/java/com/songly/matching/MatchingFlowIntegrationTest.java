package com.songly.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class MatchingFlowIntegrationTest extends AbstractIntegrationTest {

	private static final String GENRE_REGGAE = "a5a9f290-1111-4a2e-8b1a-00000000000b";
	private static final String GENRE_LATIN = "a5a9f290-1111-4a2e-8b1a-00000000000c";
	private static final String GENRE_KPOP = "a5a9f290-1111-4a2e-8b1a-00000000000d";
	private static final String GENRE_FOLK = "a5a9f290-1111-4a2e-8b1a-00000000000e";
	private static final String GENRE_PUNK = "a5a9f290-1111-4a2e-8b1a-00000000000f";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record TestUser(String accessToken, String userId) {
	}

	private TestUser registerUser(String email) throws Exception {
		MvcResult registerResult = registerAndVerify(email, "password123");
		String accessToken = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("accessToken")
				.asText();
		MvcResult profileResult = mockMvc
				.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		String userId = objectMapper.readTree(profileResult.getResponse().getContentAsString()).get("userId").asText();
		return new TestUser(accessToken, userId);
	}

	private void setGenres(TestUser user, List<String> genreIds) throws Exception {
		String payload = genreIds.stream().map("\"%s\""::formatted).collect(Collectors.joining(","));
		mockMvc.perform(put("/api/v1/profiles/me/genres").header("Authorization", "Bearer " + user.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"genreIds":[%s]}""".formatted(payload))).andExpect(status().isNoContent());
	}

	private JsonNode matches(TestUser viewer) throws Exception {
		MvcResult result = mockMvc
				.perform(get("/api/v1/matches?size=100").header("Authorization", "Bearer " + viewer.accessToken()))
				.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
	}

	private List<String> matchedUserIds(TestUser viewer) throws Exception {
		List<String> userIds = new ArrayList<>();
		matches(viewer).forEach(match -> userIds.add(match.get("userId").asText()));
		return userIds;
	}

	private double scoreOf(JsonNode matches, TestUser candidate) {
		return fieldOf(matches, candidate, "score");
	}

	private double confidenceOf(JsonNode matches, TestUser candidate) {
		return fieldOf(matches, candidate, "confidence");
	}

	private double fieldOf(JsonNode matches, TestUser candidate, String field) {
		for (JsonNode match : matches) {
			if (match.get("userId").asText().equals(candidate.userId())) {
				return match.get(field).asDouble();
			}
		}
		throw new AssertionError("brak dopasowania do " + candidate.userId());
	}

	@Test
	void matches_reportSharedGenres() throws Exception {
		TestUser ada = registerUser("match-ada@songly.io");
		TestUser bono = registerUser("match-bono@songly.io");
		TestUser carol = registerUser("match-carol@songly.io");

		setGenres(ada, List.of(GENRE_REGGAE, GENRE_LATIN, GENRE_KPOP));
		setGenres(bono, List.of(GENRE_REGGAE, GENRE_LATIN, GENRE_FOLK));
		setGenres(carol, List.of(GENRE_KPOP));

		mockMvc.perform(get("/api/v1/matches?size=100").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.userId == '%s')].sharedGenreIds".formatted(bono.userId()),
						hasItem(containsInAnyOrder(GENRE_REGGAE, GENRE_LATIN))))
				.andExpect(jsonPath("$.content[?(@.userId == '%s')].sharedGenreIds".formatted(carol.userId()),
						hasItem(contains(GENRE_KPOP))));

		JsonNode matches = matches(ada);
		assertThat(scoreOf(matches, bono)).isGreaterThan(0.0).isLessThan(1.0);
		assertThat(scoreOf(matches, carol)).isGreaterThan(0.0).isLessThan(1.0);
	}

	@Test
	void matches_rankRareSharedGenreAboveMainstreamOne() throws Exception {
		TestUser viewer = registerUser("wmatch-viewer@songly.io");
		TestUser nicheTwin = registerUser("wmatch-niche@songly.io");
		TestUser mainstreamTwin = registerUser("wmatch-mainstream@songly.io");
		for (int i = 0; i < 6; i++) {
			setGenres(registerUser("wmatch-crowd-%d@songly.io".formatted(i)), List.of(GENRE_REGGAE));
		}

		setGenres(viewer, List.of(GENRE_FOLK, GENRE_REGGAE));
		setGenres(nicheTwin, List.of(GENRE_FOLK, GENRE_LATIN));
		setGenres(mainstreamTwin, List.of(GENRE_REGGAE, GENRE_LATIN));

		JsonNode matches = matches(viewer);
		assertThat(scoreOf(matches, nicheTwin)).isGreaterThan(scoreOf(matches, mainstreamTwin));

		List<String> ranking = matchedUserIds(viewer);
		assertThat(ranking.indexOf(nicheTwin.userId())).isLessThan(ranking.indexOf(mainstreamTwin.userId()));
	}

	@Test
	void matches_scoreGrowsWithHowMuchBothProfilesTell() throws Exception {
		TestUser poorViewer = registerUser("conf-poor-viewer@songly.io");
		TestUser poorTwin = registerUser("conf-poor-twin@songly.io");
		TestUser richViewer = registerUser("conf-rich-viewer@songly.io");
		TestUser richTwin = registerUser("conf-rich-twin@songly.io");

		setGenres(poorViewer, List.of(GENRE_REGGAE));
		setGenres(poorTwin, List.of(GENRE_REGGAE));
		List<String> everything = List.of(GENRE_REGGAE, GENRE_LATIN, GENRE_KPOP, GENRE_FOLK, GENRE_PUNK);
		setGenres(richViewer, everything);
		setGenres(richTwin, everything);

		double poorScore = scoreOf(matches(poorViewer), poorTwin);
		double richScore = scoreOf(matches(richViewer), richTwin);

		assertThat(richScore).isGreaterThan(poorScore);
		assertThat(richScore).isLessThan(1.0);

		double poorConfidence = confidenceOf(matches(poorViewer), poorTwin);
		assertThat(poorConfidence).isGreaterThan(0.0).isLessThan(1.0);
		assertThat(confidenceOf(matches(richViewer), richTwin)).isGreaterThan(poorConfidence);
	}

	@Test
	void matches_excludeFriends_andUsersWithoutSharedGenres() throws Exception {
		TestUser ada = registerUser("match-friend-ada@songly.io");
		TestUser bono = registerUser("match-friend-bono@songly.io");

		setGenres(ada, List.of(GENRE_PUNK));
		setGenres(bono, List.of(GENRE_PUNK));

		assertThat(matchedUserIds(ada)).contains(bono.userId());

		MvcResult requestResult = mockMvc
				.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + ada.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"addresseeId":"%s"}""".formatted(bono.userId())))
				.andExpect(status().isCreated()).andReturn();
		String requestId = objectMapper.readTree(requestResult.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isOk());

		assertThat(matchedUserIds(ada)).doesNotContain(bono.userId());
	}

	@Test
	void matches_emptyWithoutFavoriteGenres() throws Exception {
		TestUser ada = registerUser("match-nogenres@songly.io");

		mockMvc.perform(get("/api/v1/matches").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
	}

	@Test
	void matches_requireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/matches")).andExpect(status().isUnauthorized());
	}
}
