package com.songly.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Publiczny profil innego użytkownika (GET /api/v1/profiles/{userId}) — do
 * oglądania cudzego profilu z aplikacji. Zwraca dane profilowe wraz z
 * ulubionymi gatunkami; świadomie nie niesie danych o zachowaniu (Claude.md
 * §9).
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class PublicProfileIntegrationTest extends AbstractIntegrationTest {

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

	/**
	 * userId = podmiot (sub) tokena JWT; to nim posługuje się backend jako
	 * principalem.
	 */
	private String userIdFromToken(String accessToken) {
		String payload = accessToken.split("\\.")[1];
		String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
		return objectMapper.readTree(json).get("sub").asText();
	}

	@Test
	void publicProfile_returnsProfileWithFavoriteGenres() throws Exception {
		String viewerToken = registerAndGetAccessToken("public-viewer@songly.io");
		String targetToken = registerAndGetAccessToken("public-target@songly.io");
		String targetUserId = userIdFromToken(targetToken);

		mockMvc.perform(get("/api/v1/profiles/{userId}", targetUserId).header("Authorization", "Bearer " + viewerToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(targetUserId))
				.andExpect(jsonPath("$.displayName").value("public-target"))
				.andExpect(jsonPath("$.favoriteGenres").isArray());
	}

	@Test
	void publicProfile_unknownUser_returnsNotFound() throws Exception {
		String viewerToken = registerAndGetAccessToken("public-notfound@songly.io");

		mockMvc.perform(
				get("/api/v1/profiles/{userId}", UUID.randomUUID()).header("Authorization", "Bearer " + viewerToken))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
	}

	@Test
	void publicProfile_malformedUserId_returnsBadRequest() throws Exception {
		String viewerToken = registerAndGetAccessToken("public-malformed@songly.io");

		mockMvc.perform(get("/api/v1/profiles/{userId}", "not-a-uuid").header("Authorization", "Bearer " + viewerToken))
				.andExpect(status().isBadRequest());
	}

	@Test
	void publicProfile_withoutAuth_returnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/profiles/{userId}", UUID.randomUUID())).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}
}
