package com.songly.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Kontener bazy jest współdzielony między wszystkimi klasami testowymi
 * (AbstractIntegrationTest), więc liczniki nie są tu porównywane wprost —
 * tylko delta przed/po akcjach w obrębie jednego testu, żeby nie zależeć od
 * tego, co inne klasy zdążyły już zapisać.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class AnalyticsFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

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

	private TestUser registerAdmin(String email) throws Exception {
		TestUser user = registerUser(email);
		jdbcTemplate.update("UPDATE users SET is_admin = TRUE WHERE id = ?", UUID.fromString(user.userId()));
		return user;
	}

	private Map<String, Long> fetchCounts(String accessToken) throws Exception {
		MvcResult result = mockMvc
				.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		Map<String, Long> counts = new HashMap<>();
		objectMapper.readTree(result.getResponse().getContentAsString()).get("counts")
				.forEach(node -> counts.put(node.get("type").asText(), node.get("count").asLong()));
		return counts;
	}

	@Test
	void summary_reflectsDomainActivity_asDeltaSinceBefore() throws Exception {
		TestUser ada = registerAdmin("analytics-ada@songly.io");
		TestUser bono = registerUser("analytics-bono@songly.io");

		Map<String, Long> before = fetchCounts(ada.accessToken());

		registerUser("analytics-charlie@songly.io");

		MvcResult requestResult = mockMvc.perform(post("/api/v1/friends/requests")
				.header("Authorization", "Bearer " + ada.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"addresseeId":"%s"}""".formatted(bono.userId()))).andExpect(status().isCreated()).andReturn();
		String requestId = objectMapper.readTree(requestResult.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId))
				.header("Authorization", "Bearer " + bono.accessToken())).andExpect(status().isOk());

		MvcResult postResult = mockMvc.perform(post("/api/v1/posts")
				.header("Authorization", "Bearer " + ada.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"content":"analytics test"}""")).andExpect(status().isCreated()).andReturn();
		String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/posts/" + postId + "/comments")
				.header("Authorization", "Bearer " + bono.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"content":"super"}""")).andExpect(status().isCreated());
		mockMvc.perform(put("/api/v1/posts/" + postId + "/shares").header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isNoContent());

		MvcResult communityResult = mockMvc.perform(post("/api/v1/communities")
				.header("Authorization", "Bearer " + ada.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Analytics fanklub","description":"test"}""")).andExpect(status().isCreated())
				.andReturn();
		String communityId = objectMapper.readTree(communityResult.getResponse().getContentAsString()).get("id")
				.asText();
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isNoContent());

		Map<String, Long> after = fetchCounts(ada.accessToken());

		assertThat(after.get("USER_REGISTERED") - before.get("USER_REGISTERED")).isEqualTo(1);
		assertThat(after.get("FRIEND_REQUEST_SENT") - before.get("FRIEND_REQUEST_SENT")).isEqualTo(1);
		assertThat(after.get("FRIEND_REQUEST_ACCEPTED") - before.get("FRIEND_REQUEST_ACCEPTED")).isEqualTo(1);
		assertThat(after.get("POST_LIKED") - before.get("POST_LIKED")).isEqualTo(1);
		assertThat(after.get("POST_COMMENTED") - before.get("POST_COMMENTED")).isEqualTo(1);
		assertThat(after.get("POST_SHARED") - before.get("POST_SHARED")).isEqualTo(1);
		assertThat(after.get("COMMUNITY_MEMBER_JOINED") - before.get("COMMUNITY_MEMBER_JOINED")).isEqualTo(1);
	}

	@Test
	void analyticsEndpoints_requireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/analytics/summary")).andExpect(status().isUnauthorized());
	}

	@Test
	void analyticsEndpoints_requireAdminRole() throws Exception {
		TestUser regularUser = registerUser("analytics-non-admin@songly.io");
		mockMvc.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + regularUser.accessToken()))
				.andExpect(status().isForbidden());
	}
}
