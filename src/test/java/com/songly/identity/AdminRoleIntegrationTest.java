package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Zarządzanie rolą administratora (ADR 0021). ADR 0006 zostawił promocję jako
 * ręczny {@code UPDATE} w bazie „do czasu, aż powstanie panel admina" — ten
 * test pilnuje, że już nie jest potrzebny, i że droga do eskalacji uprawnień ma
 * domknięte brzegi.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class AdminRoleIntegrationTest extends AbstractIntegrationTest {

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

	/** Pierwszy administrator z natury rzeczy musi powstać w bazie (ADR 0021). */
	private TestUser bootstrapAdmin(String email) throws Exception {
		TestUser user = user(email);
		jdbcTemplate.update("UPDATE users SET is_admin = TRUE WHERE id = ?", UUID.fromString(user.userId()));
		return user;
	}

	private List<String> admins(TestUser admin) throws Exception {
		JsonNode body = objectMapper.readTree(
				mockMvc.perform(get("/api/v1/admin/admins").header("Authorization", "Bearer " + admin.token()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		List<String> ids = new ArrayList<>();
		for (JsonNode id : body.get("userIds")) {
			ids.add(id.asText());
		}
		return ids;
	}

	/**
	 * Promocja działa i od razu daje realne uprawnienia — sprawdzane na endpoincie
	 * gated rolą, bo to dopiero ono dowodzi, że kolumna cokolwiek znaczy.
	 * Uprawnienie czytamy na żywo z bazy (ADR 0006), więc promowany nie musi się
	 * przelogowywać.
	 */
	@Test
	void adminCanPromoteAnotherUserWhoImmediatelyGainsAccess() throws Exception {
		TestUser admin = bootstrapAdmin("adminrole-grant-admin@songly.io");
		TestUser promoted = user("adminrole-grant-target@songly.io");

		mockMvc.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + promoted.token()))
				.andExpect(status().isForbidden());

		mockMvc.perform(put("/api/v1/admin/users/" + promoted.userId() + "/admin").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + promoted.token()))
				.andExpect(status().isOk());
		assertThat(admins(admin)).contains(promoted.userId());

		// Idempotentne: powtórzone nadanie nie jest błędem.
		mockMvc.perform(put("/api/v1/admin/users/" + promoted.userId() + "/admin").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());
	}

	/**
	 * Degradacja też działa na żywo — odebranie uprawnień nie wymaga unieważniania
	 * sesji, bo rola nie siedzi w tokenie.
	 */
	@Test
	void revokedAdminLosesAccessImmediately() throws Exception {
		TestUser admin = bootstrapAdmin("adminrole-revoke-admin@songly.io");
		TestUser other = user("adminrole-revoke-target@songly.io");
		mockMvc.perform(put("/api/v1/admin/users/" + other.userId() + "/admin").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isOk());

		mockMvc.perform(delete("/api/v1/admin/users/" + other.userId() + "/admin").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isForbidden());
		assertThat(admins(admin)).doesNotContain(other.userId());

		// Idempotentne: odebranie uprawnień komuś, kto ich nie ma, nie jest błędem.
		mockMvc.perform(delete("/api/v1/admin/users/" + other.userId() + "/admin").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());
	}

	/**
	 * Niezmiennik ADR 0021: nikt nie degraduje siebie, a degradować może wyłącznie
	 * administrator — więc w systemie zawsze zostaje co najmniej jeden i nigdy nie
	 * trzeba wracać do ręcznego SQL-a po dostęp.
	 */
	@Test
	void adminCannotRevokeTheirOwnRole() throws Exception {
		TestUser admin = bootstrapAdmin("adminrole-self@songly.io");

		mockMvc.perform(delete("/api/v1/admin/users/" + admin.userId() + "/admin").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CANNOT_REVOKE_OWN_ADMIN"));

		assertThat(admins(admin)).contains(admin.userId());
	}

	/**
	 * Zwykły użytkownik nie dotyka niczego pod /admin — gating jest na prefiksie.
	 */
	@Test
	void nonAdminIsRejectedOnEveryAdminEndpoint() throws Exception {
		TestUser someone = user("adminrole-outsider@songly.io");
		TestUser victim = user("adminrole-outsider-target@songly.io");

		mockMvc.perform(get("/api/v1/admin/admins").header("Authorization", "Bearer " + someone.token()))
				.andExpect(status().isForbidden());
		mockMvc.perform(put("/api/v1/admin/users/" + victim.userId() + "/admin").header("Authorization",
				"Bearer " + someone.token())).andExpect(status().isForbidden());
		// Przede wszystkim: nie da się promować samego siebie.
		mockMvc.perform(put("/api/v1/admin/users/" + someone.userId() + "/admin").header("Authorization",
				"Bearer " + someone.token())).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + someone.token()))
				.andExpect(status().isForbidden());
	}

	@Test
	void grantingToUnknownUserIsNotFound() throws Exception {
		TestUser admin = bootstrapAdmin("adminrole-404@songly.io");

		mockMvc.perform(put("/api/v1/admin/users/" + UUID.randomUUID() + "/admin").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
	}
}
