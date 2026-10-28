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
 * Globalne zawieszanie kont (ADR 0022).
 *
 * <p>
 * Najważniejsze są tu dwie rzeczy: że zawieszenie naprawdę odcina
 * <b>wszystko</b> (bo egzekucja siedzi w uprawnieniach, a nie w sprawdzeniach
 * rozsianych po modułach) i że mimo tego zostawia otwarte usunięcie konta —
 * prawo z RODO (Claude.md §9), którego sankcja nie może unieważniać.
 */
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

	/**
	 * Zawieszenie odcina każdą ścieżkę, nie tylko zapis — i zostawia dokładnie to,
	 * co musi zostać. Odczyt feedu jest tu równie ważny jak publikowanie:
	 * zawieszone konto nie ma być obecne w produkcie.
	 */
	@Test
	void suspendedAccountLosesEverythingExceptItsOwnStatusAndDeletion() throws Exception {
		TestUser admin = admin("suspension-cut-admin@songly.io");
		TestUser troll = user("suspension-cut-troll@songly.io");

		suspend(admin, troll, null);

		// Uprawnienie czytamy na żywo z bazy, więc stary token traci moc bez
		// wylogowywania (ta sama własność co przy odebraniu roli admina, ADR 0021).
		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + troll.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"nie powinno przejsc"}""")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isForbidden());

		// ...a to zostaje: własny stan zawieszenia z powodem i bez terminu.
		JsonNode suspension = objectMapper.readTree(
				mockMvc.perform(get("/api/v1/auth/me/suspension").header("Authorization", "Bearer " + troll.token()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(suspension.get("suspended").asBoolean()).isTrue();
		assertThat(suspension.get("reason").asText()).isEqualTo("nękanie innych");
		assertThat(suspension.get("expiresAt").isNull()).isTrue();
	}

	/**
	 * Prawo do usunięcia danych (RODO, §9) przeżywa zawieszenie — gdyby nie
	 * przeżywało, sankcja odbierałaby uprawnienie, którego nie wolno odebrać.
	 */
	@Test
	void suspendedAccountCanStillDeleteItself() throws Exception {
		TestUser admin = admin("suspension-rodo-admin@songly.io");
		TestUser troll = user("suspension-rodo-troll@songly.io");
		suspend(admin, troll, null);

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + troll.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"%s"}""".formatted(troll.password()))).andExpect(status().isNoContent());

		// Konto znika, więc logowanie przestaje działać w ogóle.
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"%s"}""".formatted(troll.email(), troll.password())))
				.andExpect(status().isUnauthorized());
	}

	/**
	 * Logowanie zawieszonego <b>przechodzi</b> i to jest celowe: bez tokenu nie
	 * miałby jak poznać powodu ani usunąć konta. Wydane uprawnienia są przy tym
	 * puste, co sprawdza test wyżej.
	 */
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

	/** Wygasanie jest leniwe: po terminie konto działa samo, bez niczyjej akcji. */
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
		// Wygasłe zawieszenie nie jest stanem, więc nie ma go w wykazie dla admina.
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

		// Idempotentne: zdjęcie zawieszenia z konta aktywnego nie jest błędem.
		mockMvc.perform(delete("/api/v1/admin/users/" + troll.userId() + "/suspension").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());
	}

	/**
	 * Dwa zakazy przeniesione z reguł moderacji społeczności (ADR 0011): nie siebie
	 * i nie innego administratora.
	 */
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

	/**
	 * Powód jest obowiązkowy — zawieszenie bez uzasadnienia byłoby nieodtwarzalne.
	 */
	@Test
	void reasonIsRequired() throws Exception {
		TestUser admin = admin("suspension-valid-admin@songly.io");
		TestUser troll = user("suspension-valid-troll@songly.io");

		mockMvc.perform(put("/api/v1/admin/users/" + troll.userId() + "/suspension")
				.header("Authorization", "Bearer " + admin.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"  "}""")).andExpect(status().isBadRequest());
	}

	/**
	 * Mail jest jedynym kanałem powiadomienia (skrzynka w aplikacji jest dla
	 * zawieszonego niedostępna), ale <b>powodu nie niesie</b> — tekst
	 * administratora nie jedzie kanałem push (ADR 0015). Termin owszem, bo bez
	 * niego zawieszenie czasowe wygląda jak dożywotnie (ADR 0014).
	 */
	@Test
	void mailCarriesTheTermButNeverTheReason() throws Exception {
		TestUser admin = admin("suspension-mail-admin@songly.io");
		TestUser troll = user("suspension-mail-troll@songly.io");
		mails.clearFor(troll.email());

		suspend(admin, troll, 7);

		assertThat(mails.lastTo(troll.email()).subject()).contains("zawieszone");
		assertThat(mails.lastTo(troll.email()).body()).contains("wygasa za 7 dni").doesNotContain("nękanie innych");

		// Poprawka trwającego zawieszenia nie wysyła drugiego maila o tym samym.
		suspend(admin, troll, 14);
		assertThat(mails.sentTo(troll.email())).hasSize(1);

		// Zdjęcie zawieszenia zawiadamia, że konto wróciło.
		mockMvc.perform(delete("/api/v1/admin/users/" + troll.userId() + "/suspension").header("Authorization",
				"Bearer " + admin.token())).andExpect(status().isNoContent());
		assertThat(mails.lastTo(troll.email()).subject()).contains("zdjęte");
	}

	private String suspensions(TestUser admin) throws Exception {
		return mockMvc.perform(get("/api/v1/admin/suspensions").header("Authorization", "Bearer " + admin.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	/**
	 * Kilkaset milionów dni wypycha termin poza zakres znacznika czasu Postgresa —
	 * bez górnej granicy kończyło się to 500 zamiast 400 (ADR 0049).
	 */
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
