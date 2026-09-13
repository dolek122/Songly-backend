package com.songly.moderationlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
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
 * Historia moderacji (ADR 0024). Zamyka dług z czterech ADR-ów naraz: wygasłe
 * bany były niewidoczne (0014), odwołań nikt nie liczył (0017, 0023), a
 * trafności zgłoszeń nie dało się zmierzyć, bo kasowanie posta zabierało
 * kaskadą jego zgłoszenia (0019).
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class ModerationLogIntegrationTest extends AbstractIntegrationTest {

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

	private String createCommunity(TestUser owner, String name) throws Exception {
		return objectMapper
				.readTree(mockMvc
						.perform(post("/api/v1/communities").header("Authorization", "Bearer " + owner.token())
								.contentType(MediaType.APPLICATION_JSON).content("""
										{"name":"%s","description":"opis"}""".formatted(name)))
						.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
				.get("id").asText();
	}

	private void join(TestUser user, String communityId) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + user.token())).andExpect(status().isNoContent());
	}

	private void ban(TestUser moderator, String communityId, TestUser target, Integer durationDays) throws Exception {
		String body = durationDays == null ? """
				{"reason":"spam"}""" : """
				{"reason":"spam","durationDays":%d}""".formatted(durationDays);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + target.userId())
				.header("Authorization", "Bearer " + moderator.token()).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isNoContent());
	}

	private void unban(TestUser moderator, String communityId, TestUser target) throws Exception {
		mockMvc.perform(delete("/api/v1/communities/" + communityId + "/bans/" + target.userId())
				.header("Authorization", "Bearer " + moderator.token())).andExpect(status().isNoContent());
	}

	private void grantAdmin(TestUser adminUser, TestUser target) throws Exception {
		mockMvc.perform(put("/api/v1/admin/users/" + target.userId() + "/admin").header("Authorization",
				"Bearer " + adminUser.token())).andExpect(status().isNoContent());
	}

	private void revokeAdmin(TestUser adminUser, TestUser target) throws Exception {
		mockMvc.perform(delete("/api/v1/admin/users/" + target.userId() + "/admin").header("Authorization",
				"Bearer " + adminUser.token())).andExpect(status().isNoContent());
	}

	private JsonNode communityLog(TestUser moderator, String communityId) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/moderation-log").header("Authorization",
						"Bearer " + moderator.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
	}

	private JsonNode platformLog(TestUser adminUser, TestUser subject) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(get("/api/v1/admin/moderation-log?userId=" + subject.userId()).header("Authorization",
						"Bearer " + adminUser.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
	}

	private List<String> actions(JsonNode log) {
		List<String> actions = new ArrayList<>();
		for (JsonNode entry : log) {
			actions.add(entry.get("action").asText());
		}
		return actions;
	}

	/**
	 * Sedno ADR 0014: wygasły ban znika z wykazu stanu, ale zostaje w historii —
	 * dopiero to pozwala odróżnić „nigdy nie był zbanowany" od „ban wygasł".
	 */
	@Test
	void expiredBanDisappearsFromStateButStaysInHistory() throws Exception {
		TestUser owner = user("modlog-exp-owner@songly.io");
		TestUser troll = user("modlog-exp-troll@songly.io");
		String communityId = createCommunity(owner, "Modlog-wygasly");
		join(troll, communityId);
		ban(owner, communityId, troll, 7);
		jdbcTemplate.update("UPDATE community_bans SET expires_at = now() - interval '1 hour' WHERE user_id = ?",
				UUID.fromString(troll.userId()));

		// Wykaz stanu jest pusty...
		JsonNode bans = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/bans").header("Authorization",
						"Bearer " + owner.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
		assertThat(bans).isEmpty();

		// ...a historia pamięta ban razem z jego terminem.
		JsonNode log = communityLog(owner, communityId);
		assertThat(actions(log)).contains("COMMUNITY_BAN_ISSUED");
		JsonNode entry = log.get(0);
		assertThat(entry.get("subjectId").asText()).isEqualTo(troll.userId());
		assertThat(entry.get("actorId").asText()).isEqualTo(owner.userId());
		assertThat(entry.get("expiresAt").isNull()).isFalse();
	}

	/** Ban i ułaskawienie to dwa wpisy — inaczej „zbanowany 3×" nic nie znaczy. */
	@Test
	void banAndLiftAreBothRecorded() throws Exception {
		TestUser owner = user("modlog-lift-owner@songly.io");
		TestUser troll = user("modlog-lift-troll@songly.io");
		String communityId = createCommunity(owner, "Modlog-ulaskawienie");
		join(troll, communityId);

		ban(owner, communityId, troll, null);
		unban(owner, communityId, troll);

		assertThat(actions(communityLog(owner, communityId))).containsExactly("COMMUNITY_BAN_LIFTED",
				"COMMUNITY_BAN_ISSUED");
	}

	/**
	 * Sedno ADR 0019: liczba zgłoszeń zapisana w chwili usunięcia posta. Po fakcie
	 * nie ma już czego zliczyć, bo kasowanie posta zabiera kaskadą jego zgłoszenia.
	 */
	@Test
	void postRemovalRecordsHowManyReportsItHad() throws Exception {
		TestUser owner = user("modlog-rep-owner@songly.io");
		TestUser author = user("modlog-rep-author@songly.io");
		TestUser firstReporter = user("modlog-rep-rep1@songly.io");
		TestUser secondReporter = user("modlog-rep-rep2@songly.io");
		String communityId = createCommunity(owner, "Modlog-zgloszenia");
		join(author, communityId);
		String postId = objectMapper.readTree(mockMvc
				.perform(post("/api/v1/communities/" + communityId + "/posts")
						.header("Authorization", "Bearer " + author.token()).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":"sporny post"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
		for (TestUser reporter : List.of(firstReporter, secondReporter)) {
			mockMvc.perform(
					post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + reporter.token())
							.contentType(MediaType.APPLICATION_JSON).content("""
									{"reason":"SPAM"}"""))
					.andExpect(status().isAccepted());
		}

		mockMvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());

		JsonNode entry = communityLog(owner, communityId).get(0);
		assertThat(entry.get("action").asText()).isEqualTo("COMMUNITY_POST_REMOVED");
		assertThat(entry.get("reportCount").asInt()).isEqualTo(2);
		assertThat(entry.get("subjectId").asText()).isEqualTo(author.userId());
	}

	/** Odwołania i ich rozstrzygnięcia — to ich brak zapisały ADR 0017 i 0023. */
	@Test
	void appealsAreRecordedOnBothSides() throws Exception {
		TestUser owner = user("modlog-app-owner@songly.io");
		TestUser troll = user("modlog-app-troll@songly.io");
		String communityId = createCommunity(owner, "Modlog-odwolania");
		join(troll, communityId);
		ban(owner, communityId, troll, null);
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"nieporozumienie"}""")).andExpect(status().isNoContent());

		unban(owner, communityId, troll);

		List<String> actions = actions(communityLog(owner, communityId));
		assertThat(actions).contains("COMMUNITY_BAN_ISSUED", "COMMUNITY_BAN_APPEAL_SUBMITTED",
				"COMMUNITY_BAN_APPEAL_ACCEPTED", "COMMUNITY_BAN_LIFTED");
	}

	/**
	 * Historia platformowa odpowiada na pytanie, którego nie dało się dotąd zadać
	 * nigdzie: „co ten człowiek ma za sobą" — i spina decyzje społecznościowe z
	 * platformowymi.
	 */
	@Test
	void platformLogSpansCommunityAndPlatformDecisions() throws Exception {
		TestUser adminUser = admin("modlog-plat-admin@songly.io");
		TestUser owner = user("modlog-plat-owner@songly.io");
		TestUser troll = user("modlog-plat-troll@songly.io");
		String communityId = createCommunity(owner, "Modlog-platforma");
		join(troll, communityId);
		ban(owner, communityId, troll, null);
		mockMvc.perform(put("/api/v1/admin/users/" + troll.userId() + "/suspension")
				.header("Authorization", "Bearer " + adminUser.token()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reason":"nękanie","durationDays":7}""")).andExpect(status().isNoContent());

		JsonNode log = platformLog(adminUser, troll);

		assertThat(actions(log)).containsExactly("ACCOUNT_SUSPENDED", "COMMUNITY_BAN_ISSUED");
		JsonNode suspension = log.get(0);
		assertThat(suspension.get("communityId").isNull()).isTrue();
		assertThat(suspension.get("actorId").asText()).isEqualTo(adminUser.userId());
		assertThat(suspension.get("expiresAt").isNull()).isFalse();
	}

	/**
	 * Sedno ADR 0031: nadanie i odebranie roli administratora trafia do dziennika.
	 * Dotąd zostawiało po sobie wyłącznie linijkę w logach i przyrost licznika —
	 * czyli ślad wymagający dostępu do maszyny, w odróżnieniu od każdej sankcji
	 * wobec zwykłego konta.
	 */
	@Test
	void adminRoleChangesAreRecordedAsPlatformDecisions() throws Exception {
		TestUser adminUser = admin("modlog-role-admin@songly.io");
		TestUser promoted = user("modlog-role-promoted@songly.io");

		grantAdmin(adminUser, promoted);
		revokeAdmin(adminUser, promoted);

		JsonNode log = platformLog(adminUser, promoted);
		assertThat(actions(log)).containsExactly("PLATFORM_ADMIN_REVOKED", "PLATFORM_ADMIN_GRANTED");
		JsonNode revocation = log.get(0);
		// Rola jest platformowa, więc wpis nie należy do żadnej społeczności — tak samo
		// jak zawieszenie konta (ADR 0022).
		assertThat(revocation.get("communityId").isNull()).isTrue();
		assertThat(revocation.get("subjectId").asText()).isEqualTo(promoted.userId());
		assertThat(revocation.get("actorId").asText()).isEqualTo(adminUser.userId());
		assertThat(revocation.get("expiresAt").isNull()).isTrue();
	}

	/**
	 * Bramka „tylko faktyczna zmiana" siedzi u publikującego zdarzenie (ADR 0021 i
	 * 0031), więc dziennik dziedziczy ją za darmo: nadanie roli komuś, kto ją ma,
	 * jest idempotentnym brakiem zmiany i nie ma czego zapisywać.
	 */
	@Test
	void repeatedGrantDoesNotAddASecondEntry() throws Exception {
		TestUser adminUser = admin("modlog-role-idem-admin@songly.io");
		TestUser promoted = user("modlog-role-idem-promoted@songly.io");

		grantAdmin(adminUser, promoted);
		grantAdmin(adminUser, promoted);

		assertThat(actions(platformLog(adminUser, promoted))).containsExactly("PLATFORM_ADMIN_GRANTED");
	}

	/** Historię społeczności czyta jej moderacja, nie dowolny członek. */
	@Test
	void communityLogRequiresModerator() throws Exception {
		TestUser owner = user("modlog-perm-owner@songly.io");
		TestUser member = user("modlog-perm-member@songly.io");
		String communityId = createCommunity(owner, "Modlog-uprawnienia");
		join(member, communityId);

		mockMvc.perform(get("/api/v1/communities/" + communityId + "/moderation-log").header("Authorization",
				"Bearer " + member.token())).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/communities/" + UUID.randomUUID() + "/moderation-log").header("Authorization",
				"Bearer " + owner.token())).andExpect(status().isNotFound());
		// Historia platformowa jest tylko dla adminów (gating na prefiksie).
		mockMvc.perform(get("/api/v1/admin/moderation-log").header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isForbidden());
	}

	/**
	 * Usunięcie konta zabiera wpisy <b>o</b> tej osobie, ale wpisy <b>jej
	 * autorstwa</b> zostawia bez autora — ta sama asymetria co przy banach (ADR
	 * 0011).
	 *
	 * <p>
	 * Banuje tu <b>moderator, nie właściciel</b>, i to nie jest kosmetyka: gdyby
	 * autorem decyzji był właściciel, jego konto zabrałoby przy kasowaniu całą
	 * społeczność, a z nią jej historię — i nie byłoby czego osierocić (osobny test
	 * niżej).
	 */
	@Test
	void accountDeletionRemovesOwnEntriesAndOrphansAuthoredOnes() throws Exception {
		TestUser adminUser = admin("modlog-del-admin@songly.io");
		TestUser owner = user("modlog-del-owner@songly.io");
		TestUser moderator = user("modlog-del-mod@songly.io");
		TestUser troll = user("modlog-del-troll@songly.io");
		String communityId = createCommunity(owner, "Modlog-kasowanie");
		join(moderator, communityId);
		join(troll, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/members/" + moderator.userId() + "/role")
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"role":"MODERATOR"}""")).andExpect(status().isNoContent());
		ban(moderator, communityId, troll, null);
		assertThat(platformLog(adminUser, troll)).isNotEmpty();

		// Moderator kasuje konto: jego decyzja zostaje, traci tylko autora.
		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + moderator.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"password123"}""")).andExpect(status().isNoContent());
		JsonNode afterModeratorLeft = platformLog(adminUser, troll);
		assertThat(afterModeratorLeft).isNotEmpty();
		assertThat(afterModeratorLeft.get(0).get("actorId").isNull()).isTrue();

		// Zbanowany kasuje konto: historia sankcji wobec niego znika razem z nim.
		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + troll.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"password123"}""")).andExpect(status().isNoContent());
		assertThat(platformLog(adminUser, troll)).isEmpty();
	}

	/**
	 * Historia społeczności znika razem z nią — w praktyce także wtedy, gdy
	 * właściciel kasuje konto, bo to usuwa jego społeczności. Wpis bez społeczności
	 * nie miałby jak zostać odczytany, więc zostawiałby tylko rosnącą tabelę.
	 */
	@Test
	void communityDeletionRemovesItsHistory() throws Exception {
		TestUser adminUser = admin("modlog-comm-admin@songly.io");
		TestUser owner = user("modlog-comm-owner@songly.io");
		TestUser troll = user("modlog-comm-troll@songly.io");
		String communityId = createCommunity(owner, "Modlog-znikajaca");
		join(troll, communityId);
		ban(owner, communityId, troll, null);
		assertThat(platformLog(adminUser, troll)).isNotEmpty();

		mockMvc.perform(delete("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());

		assertThat(platformLog(adminUser, troll)).isEmpty();
	}
}
