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

/**
 * Klasy testów integracyjnych współdzielą bazę (singleton containers), więc ten
 * test używa gatunków z końca seeda V2 (nietykanych przez inne testy) i asercji
 * filtrowanych po konkretnych userId zamiast pozycyjnych.
 *
 * <p>
 * Od ADR 0029 wynik zależy od popularności gatunków w <b>całej</b> bazie, więc
 * żadna asercja nie przybija tu konkretnej liczby — dokładna arytmetyka należy
 * do {@code MatchingServiceTest}, a tutaj pilnujemy kolejności i tego, że wagi
 * naprawdę przechodzą przez SQL.
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
		for (JsonNode match : matches) {
			if (match.get("userId").asText().equals(candidate.userId())) {
				return match.get("score").asDouble();
			}
		}
		throw new AssertionError("brak dopasowania do " + candidate.userId());
	}

	/**
	 * Kształt odpowiedzi: kto dzieli jakie gatunki i że wynik zostaje w (0, 1].
	 *
	 * <p>
	 * <b>Czego ten test celowo nie asertuje:</b> że kandydat z większą liczbą
	 * wspólnych gatunków stoi wyżej. Od ADR 0029 to nie jest już własność algorytmu
	 * — dwa wspólne gatunki mainstreamowe mogą przegrać z jednym niszowym, i o to w
	 * tej zmianie chodziło. Kolejność sprawdza test poniżej, który sam ustawia
	 * popularność, zamiast zakładać ją po liczbie trafień.
	 */
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
		assertThat(scoreOf(matches, bono)).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
		assertThat(scoreOf(matches, carol)).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
	}

	/**
	 * Sedno ADR 0029 przez prawdziwe zapytania: obaj kandydaci dzielą z pytającym
	 * <b>dokładnie jeden</b> gatunek i mają identyczne złączenie (ten sam trzeci
	 * gatunek), więc surowy Jaccard dawał tu remis 1/3, a kolejność rozstrzygał
	 * identyfikator. Waga rzadkości ustawia wyżej tego, z którym dzielimy niszę.
	 */
	@Test
	void matches_rankRareSharedGenreAboveMainstreamOne() throws Exception {
		TestUser viewer = registerUser("wmatch-viewer@songly.io");
		TestUser nicheTwin = registerUser("wmatch-niche@songly.io");
		TestUser mainstreamTwin = registerUser("wmatch-mainstream@songly.io");
		// Reggae robimy w tej bazie gatunkiem mainstreamowym; folk zostaje niszą.
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
