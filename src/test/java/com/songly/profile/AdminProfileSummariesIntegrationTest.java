package com.songly.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Nazwy dla administracji (ADR 0036). Sedno: administrator widzi nazwę osoby
 * zawieszonej, choć jej profil jest dla wszystkich ukryty (ADR 0026) —
 * wcześniej wykaz zawieszeń pokazywał wyłącznie identyfikatory, a dociągnięcie
 * nazwy z profilu kończyło się 404 akurat dla ludzi, o których admin decyduje.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class AdminProfileSummariesIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private record TestUser(String token, String userId) {
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		String userId = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText();
		return new TestUser(token, userId);
	}

	private TestUser admin(String email) throws Exception {
		TestUser user = user(email);
		jdbcTemplate.update("UPDATE users SET is_admin = TRUE WHERE id = ?", UUID.fromString(user.userId()));
		return user;
	}

	@Test
	void adminSeesNameOfSuspendedUserWhoseProfileIsHidden() throws Exception {
		TestUser admin = admin("summaries-admin@songly.io");
		TestUser target = user("summaries-target@songly.io");
		mockMvc.perform(put("/api/v1/admin/users/" + target.userId() + "/suspension")
				.header("Authorization", "Bearer " + admin.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"nękanie"}""")).andExpect(status().isNoContent());

		// Punkt wyjścia: zwykła droga do nazwy jest zamknięta także dla admina.
		mockMvc.perform(get("/api/v1/profiles/" + target.userId()).header("Authorization", "Bearer " + admin.token()))
				.andExpect(status().isNotFound());

		JsonNode body = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/admin/profiles").param("userIds", target.userId(), admin.userId())
						.header("Authorization", "Bearer " + admin.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		assertThat(body).hasSize(2);
		JsonNode suspended = null;
		for (JsonNode node : body) {
			if (node.get("userId").asText().equals(target.userId())) {
				suspended = node;
			}
		}
		assertThat(suspended).isNotNull();
		assertThat(suspended.get("displayName").asText()).isEqualTo("summaries-target");
		assertThat(suspended.get("suspended").asBoolean()).isTrue();
	}

	/**
	 * Pod /api/v1/admin/** — rola pilnowana przez SecurityConfig, nie przez
	 * kontroler.
	 */
	@Test
	void regularUserCannotUseIt() throws Exception {
		TestUser regular = user("summaries-regular@songly.io");

		mockMvc.perform(get("/api/v1/admin/profiles").param("userIds", regular.userId()).header("Authorization",
				"Bearer " + regular.token())).andExpect(status().isForbidden());
	}

	/**
	 * Usunięte konto w historii moderacji to normalny przypadek — brak w wyniku,
	 * nie błąd.
	 */
	@Test
	void unknownIdsAreSkipped() throws Exception {
		TestUser admin = admin("summaries-unknown@songly.io");

		mockMvc.perform(get("/api/v1/admin/profiles").param("userIds", UUID.randomUUID().toString())
				.header("Authorization", "Bearer " + admin.token())).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void tooManyIdsAreRejected() throws Exception {
		TestUser admin = admin("summaries-limit@songly.io");
		String ids = IntStream.range(0, ProfileService.MAX_SUMMARY_IDS + 1).mapToObj(i -> UUID.randomUUID().toString())
				.collect(Collectors.joining(","));

		mockMvc.perform(
				get("/api/v1/admin/profiles").param("userIds", ids).header("Authorization", "Bearer " + admin.token()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_USER_IDS"));
	}
}
