package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class AccountSuspensionIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private record TestUser(String token, String userId, String email, String password) {
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		String userId = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText();
		return new TestUser(token, userId, email, "password123");
	}

	private TestUser admin(String email) throws Exception {
		TestUser user = user(email);
		jdbcTemplate.update("UPDATE users SET is_admin = TRUE WHERE id = ?", UUID.fromString(user.userId()));
		return user;
	}

	private void suspend(TestUser admin, TestUser target, Integer durationDays) throws Exception {
		String body = durationDays == null ? """
				{"reason":"nękanie innych"}""" : """
				{"reason":"nękanie innych","durationDays":%d}""".formatted(durationDays);
		mockMvc.perform(put("/api/v1/admin/users/" + target.userId() + "/suspension")
				.header("Authorization", "Bearer " + admin.token()).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isNoContent());
	}

	@Test
	void suspendedAccountLosesEverythingExceptItsOwnStatusAndDeletion() throws Exception {
		TestUser admin = admin("suspension-cut-admin@songly.io");
		TestUser troll = user("suspension-cut-troll@songly.io");

		suspend(admin, troll, null);

		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + troll.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"nie powinno przejsc"}""")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
				.andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
				.andExpect(jsonPath("$.message")
						.value("Konto jest zawieszone — szczegóły pod GET /api/v1/auth/me/suspension"));
		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isForbidden());

		JsonNode suspension = objectMapper.readTree(
				mockMvc.perform(get("/api/v1/auth/me/suspension").header("Authorization", "Bearer " + troll.token()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(suspension.get("suspended").asBoolean()).isTrue();
		assertThat(suspension.get("reason").asText()).isEqualTo("nękanie innych");
		assertThat(suspension.get("expiresAt").isNull()).isTrue();
	}

	@Test
	void suspendedAccountCanStillDeleteItself() throws Exception {
		TestUser admin = admin("suspension-rodo-admin@songly.io");
		TestUser troll = user("suspension-rodo-troll@songly.io");
		suspend(admin, troll, null);

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + troll.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"%s"}""".formatted(troll.password()))).andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"%s"}""".formatted(troll.email(), troll.password())))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void suspendedAccountCanStillLogIn() throws Exception {
		TestUser admin = admin("suspension-login-admin@songly.io");
		TestUser troll = user("suspension-login-troll@songly.io");
		suspend(admin, troll, null);

		String freshToken = objectMapper
				.readTree(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"%s"}""".formatted(troll.email(), troll.password())))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("accessToken").asText();

		mockMvc.perform(get("/api/v1/auth/me/suspension").header("Authorization", "Bearer " + freshToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.suspended").value(true));
		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + freshToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void expiredSuspensionRestoresTheAccountByItself() throws Exception {
		TestUser admin = admin("suspension-exp-admin@songly.io");
		TestUser troll = user("suspension-exp-troll@songly.io");
		suspend(admin, troll, 7);
		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isForbidden());

		OffsetDateTime past = Instant.now().minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
		jdbcTemplate.update("UPDATE users SET suspension_expires_at = ? WHERE id = ?", past,
				UUID.fromString(troll.userId()));

		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/auth/me/suspension").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.suspended").value(false));
		assertThat(suspensions(admin)).doesNotContain(troll.userId());
	}

	@Test
	void liftingRestoresTheAccountAndListsOnlyActiveSuspensions() throws Exception {
		TestUser admin = admin("suspension-lift-admin@songly.io");
		TestUser troll = user("suspension-lift-troll@songly.io");
		suspend(admin, troll, null);
		assertThat(suspensions(admin)).contains(troll.userId());

		mockMvc.perform(delete("/api/v1/admin/users/" + troll.userId() + "/suspension").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isOk());
		assertThat(suspensions(admin)).doesNotContain(troll.userId());

		mockMvc.perform(delete("/api/v1/admin/users/" + troll.userId() + "/suspension").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());
	}

	@Test
	void adminCannotSuspendThemselvesOrAnotherAdmin() throws Exception {
		TestUser admin = admin("suspension-rules-admin@songly.io");
		TestUser otherAdmin = admin("suspension-rules-admin2@songly.io");

		mockMvc.perform(put("/api/v1/admin/users/" + admin.userId() + "/suspension")
				.header("Authorization", "Bearer " + admin.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"test"}""")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CANNOT_SUSPEND_SELF"));

		mockMvc.perform(put("/api/v1/admin/users/" + otherAdmin.userId() + "/suspension")
				.header("Authorization", "Bearer " + admin.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"test"}""")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CANNOT_SUSPEND_ADMIN"));
	}

	@Test
	void reasonIsRequired() throws Exception {
		TestUser admin = admin("suspension-valid-admin@songly.io");
		TestUser troll = user("suspension-valid-troll@songly.io");

		mockMvc.perform(put("/api/v1/admin/users/" + troll.userId() + "/suspension")
				.header("Authorization", "Bearer " + admin.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"  "}""")).andExpect(status().isBadRequest());
	}

	@Test
	void mailCarriesTheTermButNeverTheReason() throws Exception {
		TestUser admin = admin("suspension-mail-admin@songly.io");
		TestUser troll = user("suspension-mail-troll@songly.io");
		mails.clearFor(troll.email());

		suspend(admin, troll, 7);

		assertThat(mails.lastTo(troll.email()).subject()).contains("zawieszone");
		assertThat(mails.lastTo(troll.email()).body()).contains("wygasa za 7 dni").doesNotContain("nękanie innych");

		suspend(admin, troll, 14);
		assertThat(mails.sentTo(troll.email())).hasSize(1);

		mockMvc.perform(delete("/api/v1/admin/users/" + troll.userId() + "/suspension").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());
		assertThat(mails.lastTo(troll.email()).subject()).contains("zdjęte");
	}

	private String suspensions(TestUser admin) throws Exception {
		return mockMvc.perform(get("/api/v1/admin/suspensions").header("Authorization", "Bearer " + admin.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void absurdlyLongSuspensionIsRejected() throws Exception {
		TestUser admin = admin("suspension-huge-admin@songly.io");
		TestUser troll = user("suspension-huge-troll@songly.io");

		mockMvc.perform(put("/api/v1/admin/users/" + troll.userId() + "/suspension")
				.header("Authorization", "Bearer " + admin.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"nękanie innych","durationDays":2000000000}""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}
}
