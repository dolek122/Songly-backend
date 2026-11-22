package com.songly.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class DefaultDisplayNameIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void newProfile_getsNeutralName_thatRevealsNothingFromTheEmailAddress() throws Exception {
		String email = "jan.kowalski.default-name@songly.io";
		String accessToken = registerWithoutNaming(email);

		JsonNode me = readJson(
				mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
						.andExpect(status().isOk()).andReturn());
		String displayName = me.get("displayName").asText();

		assertThat(displayName).matches("Użytkownik \\d{6}");
		assertThat(displayName.toLowerCase()).doesNotContain("jan").doesNotContain("kowalski");

		String searcher = registerWithoutNaming("default-name-searcher@songly.io");
		JsonNode found = readJson(mockMvc.perform(
				get("/api/v1/profiles/search").param("query", "kowalski").header("Authorization", "Bearer " + searcher))
				.andExpect(status().isOk()).andReturn());
		assertThat(found.get("content")).isEmpty();
	}

	private String registerWithoutNaming(String email) throws Exception {
		mails.clearFor(email);
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isAccepted());
		MvcResult verified = mockMvc
				.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
						{"token":"%s"}""".formatted(verificationTokenFor(email)))).andExpect(status().isOk())
				.andReturn();
		return readJson(verified).get("accessToken").asText();
	}

	private JsonNode readJson(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
