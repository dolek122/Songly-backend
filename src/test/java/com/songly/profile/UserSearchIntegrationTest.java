package com.songly.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * Wyszukiwarka użytkowników (GET /api/v1/profiles/search) — umożliwia
 * znalezienie osób do dodania do znajomych. Domyślna nazwa wyświetlana bierze
 * się z lokalnej części adresu e-mail (np. searchable-alice@songly.io ->
 * "searchable-alice").
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class UserSearchIntegrationTest extends AbstractIntegrationTest {

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

	@Test
	void search_findsMatchingUsers_caseInsensitive_andExcludesSelf() throws Exception {
		String searcherToken = registerAndGetAccessToken("searchable-searcher@songly.io");
		registerAndGetAccessToken("searchable-melomanka@songly.io");
		registerAndGetAccessToken("searchable-meloman@songly.io");

		// "MELOMAN" pasuje do obu (case-insensitive), ale nie do szukającego.
		mockMvc.perform(get("/api/v1/profiles/search").header("Authorization", "Bearer " + searcherToken).param("query",
				"MELOMAN")).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].userId").exists())
				.andExpect(jsonPath("$.content[0].displayName").exists());
	}

	@Test
	void search_doesNotReturnSelf_evenWhenNameMatches() throws Exception {
		String token = registerAndGetAccessToken("searchable-solo@songly.io");

		mockMvc.perform(get("/api/v1/profiles/search").header("Authorization", "Bearer " + token).param("query",
				"searchable-solo")).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
	}

	@Test
	void search_withBlankQuery_returnsEmptyPage_notAllUsers() throws Exception {
		String token = registerAndGetAccessToken("searchable-blank@songly.io");

		mockMvc.perform(get("/api/v1/profiles/search").header("Authorization", "Bearer " + token).param("query", "   "))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
	}

	@Test
	void search_withoutQueryParam_returnsBadRequest() throws Exception {
		String token = registerAndGetAccessToken("searchable-noparam@songly.io");

		mockMvc.perform(get("/api/v1/profiles/search").header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
	}

	@Test
	void search_withoutAuth_returnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/profiles/search").param("query", "meloman")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}
}
