package com.songly.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
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

/**
 * Klasy testów integracyjnych współdzielą bazę (singleton containers), więc ten
 * test używa gatunków z końca seeda V2 (nietykanych przez inne testy) i asercji
 * filtrowanych po konkretnych userId zamiast pozycyjnych.
 */
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
		MvcResult registerResult = mockMvc
				.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isCreated())
				.andReturn();
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

	private List<String> matchedUserIds(TestUser viewer) throws Exception {
		MvcResult result = mockMvc
				.perform(get("/api/v1/matches?size=100").header("Authorization", "Bearer " + viewer.accessToken()))
				.andExpect(status().isOk()).andReturn();
		JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
		List<String> userIds = new ArrayList<>();
		content.forEach(match -> userIds.add(match.get("userId").asText()));
		return userIds;
	}

	@Test
	void matches_rankedByGenreSimilarity() throws Exception {
		TestUser ada = registerUser("match-ada@songly.io");
		TestUser bono = registerUser("match-bono@songly.io");
		TestUser carol = registerUser("match-carol@songly.io");

		setGenres(ada, List.of(GENRE_REGGAE, GENRE_LATIN, GENRE_KPOP));
		setGenres(bono, List.of(GENRE_REGGAE, GENRE_LATIN, GENRE_FOLK));
		setGenres(carol, List.of(GENRE_KPOP));

		// bono: |{reggae,latin}| / |{reggae,latin,kpop,folk}| = 0.5; carol: |{kpop}| / |{reggae,latin,kpop}| = 1/3
		mockMvc.perform(get("/api/v1/matches?size=100").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.userId == '%s')].score".formatted(bono.userId()), hasItem(0.5)))
				.andExpect(jsonPath("$.content[?(@.userId == '%s')].score".formatted(carol.userId()),
						hasItem(closeTo(1.0 / 3, 1e-9))))
				.andExpect(jsonPath("$.content[?(@.userId == '%s')].sharedGenreIds.length()".formatted(bono.userId()),
						hasItem(2)));

		List<String> ranking = matchedUserIds(ada);
		assertThat(ranking.indexOf(bono.userId())).isLessThan(ranking.indexOf(carol.userId()));
	}

	@Test
	void matches_excludeFriends_andUsersWithoutSharedGenres() throws Exception {
		TestUser ada = registerUser("match-friend-ada@songly.io");
		TestUser bono = registerUser("match-friend-bono@songly.io");

		setGenres(ada, List.of(GENRE_PUNK));
		setGenres(bono, List.of(GENRE_PUNK));

		assertThat(matchedUserIds(ada)).contains(bono.userId());

		MvcResult requestResult = mockMvc.perform(post("/api/v1/friends/requests")
				.header("Authorization", "Bearer " + ada.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"addresseeId":"%s"}""".formatted(bono.userId()))).andExpect(status().isCreated()).andReturn();
		String requestId = objectMapper.readTree(requestResult.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId))
				.header("Authorization", "Bearer " + bono.accessToken())).andExpect(status().isOk());

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
