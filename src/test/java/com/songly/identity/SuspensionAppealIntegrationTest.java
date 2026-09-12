package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * Odwołania od zawieszenia konta (ADR 0023). ADR 0022 dał zawieszonemu powód i
 * termin, ale żadnej drogi, żeby decyzję zakwestionować — a ADR 0017 ustalił,
 * że wiedzieć i nie móc nic zrobić to tylko lepiej opisana ściana.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class SuspensionAppealIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private record TestUser(String token, String userId, String email) {
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		String userId = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText();
		return new TestUser(token, userId, email);
	}

	private TestUser admin(String email) throws Exception {
		TestUser user = user(email);
		jdbcTemplate.update("UPDATE users SET is_admin = TRUE WHERE id = ?", UUID.fromString(user.userId()));
		return user;
	}

	private void suspend(TestUser admin, TestUser target, Integer durationDays) throws Exception {
		String body = durationDays == null ? """
				{"reason":"nękanie"}""" : """
				{"reason":"nękanie","durationDays":%d}""".formatted(durationDays);
		mockMvc.perform(put("/api/v1/admin/users/" + target.userId() + "/suspension")
				.header("Authorization", "Bearer " + admin.token()).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isNoContent());
	}

	private void appeal(TestUser user, String text) throws Exception {
		mockMvc.perform(post("/api/v1/auth/me/suspension/appeal").header("Authorization", "Bearer " + user.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"%s"}""".formatted(text))).andExpect(status().isNoContent());
	}

	private JsonNode ownSuspension(TestUser user) throws Exception {
		return objectMapper.readTree(
				mockMvc.perform(get("/api/v1/auth/me/suspension").header("Authorization", "Bearer " + user.token()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode suspensionEntry(TestUser admin, TestUser target) throws Exception {
		JsonNode list = objectMapper.readTree(
				mockMvc.perform(get("/api/v1/admin/suspensions").header("Authorization", "Bearer " + admin.token()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		for (JsonNode entry : list) {
			if (target.userId().equals(entry.get("userId").asText())) {
				return entry;
			}
		}
		throw new AssertionError("Brak konta " + target.userId() + " w wykazie zawieszeń");
	}

	/**
	 * Pełna droga w jedną stronę: zawieszony składa odwołanie (jednym z trzech
	 * endpointów, do których ma dostęp), administracja dostaje maila bez treści, a
	 * treść czeka w wykazie zawieszeń — czyli kanałem pull.
	 */
	@Test
	void appealReachesAdminsAndItsTextStaysInThePullChannel() throws Exception {
		TestUser admin = admin("suspappeal-flow-admin@songly.io");
		TestUser troll = user("suspappeal-flow-troll@songly.io");
		suspend(admin, troll, null);
		mails.clear();

		appeal(troll, "to byla pomylka, nie ja pisalem");

		assertThat(mails.lastTo(admin.email()).subject()).contains("odwołanie");
		assertThat(mails.lastTo(admin.email()).body()).doesNotContain("pomylka");

		JsonNode entry = suspensionEntry(admin, troll);
		assertThat(entry.get("appealText").asText()).isEqualTo("to byla pomylka, nie ja pisalem");
		assertThat(entry.get("appealStatus").asText()).isEqualTo("PENDING");

		JsonNode own = ownSuspension(troll);
		assertThat(own.get("appealStatus").asText()).isEqualTo("PENDING");
		assertThat(own.get("appealedAt").isNull()).isFalse();
	}

	/**
	 * Jedno odwołanie na zawieszenie — drugie kończy się 409, nie drugim mailem.
	 */
	@Test
	void secondAppealIsRejectedWithConflict() throws Exception {
		TestUser admin = admin("suspappeal-once-admin@songly.io");
		TestUser troll = user("suspappeal-once-troll@songly.io");
		suspend(admin, troll, null);
		appeal(troll, "pierwsze");
		mails.clearFor(admin.email());

		mockMvc.perform(post("/api/v1/auth/me/suspension/appeal").header("Authorization", "Bearer " + troll.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"drugie"}""")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUSPENSION_APPEAL_ALREADY_SUBMITTED"));

		assertThat(mails.sentTo(admin.email())).isEmpty();
	}

	@Test
	void appealWithoutAnActiveSuspensionIsRejected() throws Exception {
		TestUser admin = admin("suspappeal-nosusp-admin@songly.io");
		TestUser someone = user("suspappeal-nosusp-user@songly.io");

		// Konto aktywne nie ma od czego się odwoływać...
		mockMvc.perform(post("/api/v1/auth/me/suspension/appeal").header("Authorization", "Bearer " + someone.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"od czego ja sie odwoluje"}""")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_SUSPENSION_TO_APPEAL"));

		// ...i z zawieszeniem wygasłym też nie, bo wygasłe to brak zawieszenia.
		suspend(admin, someone, 7);
		OffsetDateTime past = Instant.now().minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
		jdbcTemplate.update("UPDATE users SET suspension_expires_at = ? WHERE id = ?", past,
				UUID.fromString(someone.userId()));
		mockMvc.perform(post("/api/v1/auth/me/suspension/appeal").header("Authorization", "Bearer " + someone.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"za pozno"}""")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_SUSPENSION_TO_APPEAL"));
	}

	/**
	 * Uznanie odwołania to zdjęcie zawieszenia — bez osobnego endpointu. Odwołujący
	 * dostaje jeden mail, mówiący <i>dlaczego</i> konto wróciło, a nie dwa o tym
	 * samym.
	 */
	@Test
	void liftingWithPendingAppealAcceptsItAndSendsOneMail() throws Exception {
		TestUser admin = admin("suspappeal-acc-admin@songly.io");
		TestUser troll = user("suspappeal-acc-troll@songly.io");
		suspend(admin, troll, null);
		appeal(troll, "obiecuje poprawe");
		mails.clearFor(troll.email());

		mockMvc.perform(delete("/api/v1/admin/users/" + troll.userId() + "/suspension").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());

		assertThat(mails.sentTo(troll.email())).hasSize(1);
		assertThat(mails.lastTo(troll.email()).subject()).contains("uznane");
		// Konto naprawdę wróciło.
		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isOk());
		assertThat(ownSuspension(troll).get("suspended").asBoolean()).isFalse();
	}

	@Test
	void rejectionKeepsTheSuspensionAndClosesTheCase() throws Exception {
		TestUser admin = admin("suspappeal-rej-admin@songly.io");
		TestUser troll = user("suspappeal-rej-troll@songly.io");
		suspend(admin, troll, null);
		appeal(troll, "nie zgadzam sie");
		mails.clearFor(troll.email());

		mockMvc.perform(post("/api/v1/admin/users/" + troll.userId() + "/suspension/appeal/rejection")
				.header("Authorization", "Bearer " + admin.token())).andExpect(status().isNoContent());

		assertThat(mails.lastTo(troll.email()).subject()).contains("odrzucone");
		assertThat(ownSuspension(troll).get("appealStatus").asText()).isEqualTo("REJECTED");
		// Konto nadal zawieszone.
		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isForbidden());

		// Drugie odrzucenie nie ma czego odrzucić.
		mockMvc.perform(post("/api/v1/admin/users/" + troll.userId() + "/suspension/appeal/rejection")
				.header("Authorization", "Bearer " + admin.token())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUSPENSION_APPEAL_NOT_FOUND"));
	}

	/**
	 * Nowe zawieszenie daje świeże prawo do odwołania, także po odrzuceniu — ale
	 * poprawienie powodu trwającego zawieszenia <b>nie</b> kasuje złożonej petycji.
	 */
	@Test
	void newSuspensionResetsTheAppealButEditingAnActiveOneDoesNot() throws Exception {
		TestUser admin = admin("suspappeal-reset-admin@songly.io");
		TestUser troll = user("suspappeal-reset-troll@songly.io");
		suspend(admin, troll, null);
		appeal(troll, "pierwsza sprawa");

		// Poprawka trwającego zawieszenia zostawia odwołanie nietknięte.
		suspend(admin, troll, 30);
		assertThat(ownSuspension(troll).get("appealStatus").asText()).isEqualTo("PENDING");

		// Zdjęcie i nałożenie od nowa to nowa sprawa — odwołanie znika.
		mockMvc.perform(delete("/api/v1/admin/users/" + troll.userId() + "/suspension").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());
		suspend(admin, troll, null);
		assertThat(ownSuspension(troll).get("appealStatus").isNull()).isTrue();
		appeal(troll, "druga sprawa");
		assertThat(suspensionEntry(admin, troll).get("appealText").asText()).isEqualTo("druga sprawa");
	}

	@Test
	void blankAppealTextIsRejected() throws Exception {
		TestUser admin = admin("suspappeal-blank-admin@songly.io");
		TestUser troll = user("suspappeal-blank-troll@songly.io");
		suspend(admin, troll, null);

		mockMvc.perform(post("/api/v1/auth/me/suspension/appeal").header("Authorization", "Bearer " + troll.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"   "}""")).andExpect(status().isBadRequest());
	}

	/** Odrzucać może administracja, nie dowolny użytkownik. */
	@Test
	void rejectionRequiresAdmin() throws Exception {
		TestUser admin = admin("suspappeal-perm-admin@songly.io");
		TestUser troll = user("suspappeal-perm-troll@songly.io");
		TestUser bystander = user("suspappeal-perm-bystander@songly.io");
		suspend(admin, troll, null);
		appeal(troll, "prosze o szanse");

		mockMvc.perform(post("/api/v1/admin/users/" + troll.userId() + "/suspension/appeal/rejection")
				.header("Authorization", "Bearer " + bystander.token())).andExpect(status().isForbidden());
	}
}
