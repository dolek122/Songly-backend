package com.songly.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class ProfileFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String registerAndGetAccessToken(String email) throws Exception {
		MvcResult result = registerAndVerify(email, "password123");
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
	}

	@Test
	void defaultProfileIsCreated_andCanBeReadAndUpdated_afterRegistration() throws Exception {
		String accessToken = registerAndGetAccessToken("profile-flow@songly.io");

		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("profile-flow"));

		mockMvc.perform(put("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"displayName":"Ada","bio":"music lover","avatarUrl":null}""")).andExpect(status().isOk())
				.andExpect(jsonPath("$.displayName").value("Ada")).andExpect(jsonPath("$.bio").value("music lover"));
	}

	@Test
	void favoriteGenres_canBeSetAndRead_whenKnown_andRejected_whenUnknown() throws Exception {
		String accessToken = registerAndGetAccessToken("genre-flow@songly.io");

		MvcResult genresResult = mockMvc.perform(get("/api/v1/genres").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		JsonNode genres = objectMapper.readTree(genresResult.getResponse().getContentAsString()).get("content");
		String firstGenreId = genres.get(0).get("id").asText();
		String secondGenreId = genres.get(1).get("id").asText();

		mockMvc.perform(put("/api/v1/profiles/me/genres").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"genreIds":["%s","%s"]}""".formatted(firstGenreId, secondGenreId)))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/profiles/me/genres").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

		mockMvc.perform(put("/api/v1/profiles/me/genres").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"genreIds":["%s"]}""".formatted(UUID.randomUUID()))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNKNOWN_GENRE"));
	}

	@Test
	void profileEndpoints_requireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/profiles/me")).andExpect(status().isUnauthorized());
	}
}
