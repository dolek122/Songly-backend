package com.songly.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kolejność list należy do serwera, a {@code sort} od klienta jest ignorowany
 * (ADR 0051). Wcześniej Spring Data doklejał go do każdego zapytania, więc
 * {@code ?sort=foo} dawał 500 na każdej liście. Tam, gdzie zapytanie ma własne
 * {@code ORDER BY}, sortowanie klienta szło za nim jako drugi klucz; tam, gdzie
 * go nie miało (wyszukiwarka, gatunki), klient wybierał kolejność sam, po
 * dowolnym polu encji.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class PaginationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String token;

	@BeforeEach
	void setUp() throws Exception {
		// Adres na test, bo baza jest wspólna dla całej suity (patrz
		// registerAndVerify).
		token = tokenOf(registerAndVerify("paging-" + UUID.randomUUID() + "@paging.test", "password123").getResponse()
				.getContentAsString());
	}

	@Test
	void unknownSortField_isIgnoredOnEveryKindOfQuery() throws Exception {
		// Po jednej liście na każdy rodzaj zapytania: pochodne z nazwy metody, JPQL i
		// findAll — każdy wywracał się na nieznanym polu innym wyjątkiem.
		for (String path : List.of("/api/v1/genres", "/api/v1/feed", "/api/v1/posts", "/api/v1/communities",
				"/api/v1/notifications", "/api/v1/profiles/search?query=paging")) {
			String separator = path.contains("?") ? "&" : "?";
			mockMvc.perform(get(path + separator + "sort=foo").header("Authorization", "Bearer " + token))
					.andExpect(status().isOk());
		}
	}

	/**
	 * Wyszukiwarka nie miała {@code ORDER BY}, więc kolejność była taka, jaką
	 * akurat zwróci Postgres, a kolejne strony mogły się nakładać.
	 */
	@Test
	void userSearch_isOrderedByName() throws Exception {
		registerAndVerify("pagina-c@paging.test", "password123");
		registerAndVerify("pagina-a@paging.test", "password123");
		registerAndVerify("pagina-b@paging.test", "password123");

		JsonNode page = getJson("/api/v1/profiles/search?query=pagina-&sort=displayName,desc");

		assertThat(names(page, "displayName")).containsExactly("pagina-a", "pagina-b", "pagina-c");
	}

	@Test
	void genres_areOrderedByName() throws Exception {
		JsonNode page = getJson("/api/v1/genres?size=100&sort=name,desc");

		List<String> names = names(page, "name");
		assertThat(names).hasSizeGreaterThan(1).isSortedAccordingTo(String::compareTo);
	}

	@Test
	void pageSizeCap_stillApplies() throws Exception {
		mockMvc.perform(get("/api/v1/genres?size=1000").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.size").value(100));
	}

	private JsonNode getJson(String path) throws Exception {
		return objectMapper.readTree(mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private static List<String> names(JsonNode page, String field) {
		List<String> names = new ArrayList<>();
		page.get("content").forEach(item -> names.add(item.get(field).asText()));
		return names;
	}

	private String tokenOf(String body) {
		return objectMapper.readTree(body).get("accessToken").asText();
	}
}
