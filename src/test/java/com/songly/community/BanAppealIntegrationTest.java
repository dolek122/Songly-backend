package com.songly.community;

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
 * Ścieżka odwoławcza od bana (ADR 0017). Zamyka ostatnią lukę z konsekwencji
 * ADR 0012: zbanowany znał już powód i termin (ADR 0015), ale nie miał żadnej
 * drogi, żeby decyzję zakwestionować.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class BanAppealIntegrationTest extends AbstractIntegrationTest {

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

	private void ban(TestUser moderator, String communityId, TestUser target) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + target.userId())
				.header("Authorization", "Bearer " + moderator.token()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reason":"spam"}""")).andExpect(status().isNoContent());
	}

	private void appeal(TestUser user, String communityId, String text) throws Exception {
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal")
				.header("Authorization", "Bearer " + user.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"%s"}""".formatted(text))).andExpect(status().isNoContent());
	}

	private long countOfType(TestUser user, String type) throws Exception {
		JsonNode content = objectMapper
				.readTree(
						mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + user.token()))
								.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("content");
		long count = 0;
		for (JsonNode node : content) {
			if (type.equals(node.get("type").asText())) {
				count++;
			}
		}
		return count;
	}

	private JsonNode membershipStatus(TestUser user, String communityId) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/membership").header("Authorization",
						"Bearer " + user.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	/**
	 * Pełna droga w jedną stronę: odwołanie → wszyscy moderatorzy zawiadomieni
	 * (skrzynka + mail bez treści odwołania) → treść czeka na liście banów → status
	 * widoczny dla odwołującego.
	 */
	@Test
	void appealReachesModeratorsAndItsTextStaysInThePullChannel() throws Exception {
		TestUser owner = user("appeal-flow-owner@songly.io");
		TestUser moderator = user("appeal-flow-mod@songly.io");
		TestUser troll = user("appeal-flow-troll@songly.io");
		String communityId = createCommunity(owner, "Appeal-przeplyw");
		join(moderator, communityId);
		join(troll, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/members/" + moderator.userId() + "/role")
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"role":"MODERATOR"}""")).andExpect(status().isNoContent());
		ban(owner, communityId, troll);
		mails.clear();

		appeal(troll, communityId, "to bylo nieporozumienie");

		assertThat(countOfType(owner, "BAN_APPEAL_SUBMITTED")).isEqualTo(1);
		assertThat(countOfType(moderator, "BAN_APPEAL_SUBMITTED")).isEqualTo(1);
		// Mail mówi, że jest po co zajrzeć — ale nie niesie treści odwołania:
		// tekst od użytkownika nie jedzie kanałem push (ta sama zasada co powód
		// bana w ADR 0015).
		assertThat(mails.lastTo(owner.email()).subject()).contains("odwołanie");
		assertThat(mails.lastTo(owner.email()).body()).doesNotContain("nieporozumienie");

		// Treść czyta moderacja na liście banów...
		JsonNode ban = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/bans").header("Authorization",
						"Bearer " + owner.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content").get(0);
		assertThat(ban.get("appealText").asText()).isEqualTo("to bylo nieporozumienie");
		assertThat(ban.get("appealStatus").asText()).isEqualTo("PENDING");

		// ...a odwołujący widzi stan sprawy w statusie członkostwa.
		JsonNode ownBan = membershipStatus(troll, communityId).get("ban");
		assertThat(ownBan.get("appealStatus").asText()).isEqualTo("PENDING");
		assertThat(ownBan.get("appealedAt").isNull()).isFalse();
	}

	/** Jedno odwołanie na ban — drugie kończy się 409, a nie drugą wiadomością. */
	@Test
	void secondAppealIsRejectedWithConflict() throws Exception {
		TestUser owner = user("appeal-once-owner@songly.io");
		TestUser troll = user("appeal-once-troll@songly.io");
		String communityId = createCommunity(owner, "Appeal-raz");
		join(troll, communityId);
		ban(owner, communityId, troll);
		appeal(troll, communityId, "pierwsze");

		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"drugie"}""")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BAN_APPEAL_ALREADY_SUBMITTED"));

		assertThat(countOfType(owner, "BAN_APPEAL_SUBMITTED")).isEqualTo(1);
	}

	@Test
	void appealWithoutAnActiveBanIsRejected() throws Exception {
		TestUser owner = user("appeal-noban-owner@songly.io");
		TestUser member = user("appeal-noban-member@songly.io");
		String communityId = createCommunity(owner, "Appeal-bezbana");
		join(member, communityId);

		// Bez bana w ogóle...
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal")
				.header("Authorization", "Bearer " + member.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"od czego ja sie odwoluje"}""")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_BAN_TO_APPEAL"));

		// ...i z banem wygasłym: wygasły ban to brak bana (ADR 0014).
		ban(owner, communityId, member);
		OffsetDateTime past = Instant.now().minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
		jdbcTemplate.update("UPDATE community_bans SET expires_at = ? WHERE community_id = ? AND user_id = ?", past,
				UUID.fromString(communityId), UUID.fromString(member.userId()));
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal")
				.header("Authorization", "Bearer " + member.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"za pozno"}""")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_BAN_TO_APPEAL"));
	}

	/**
	 * Uznanie odwołania to unban — bez osobnego endpointu. Odwołujący dostaje
	 * powiadomienie i mail, a droga powrotna naprawdę działa.
	 */
	@Test
	void unbanWithPendingAppealAcceptsItAndLetsTheUserReturn() throws Exception {
		TestUser owner = user("appeal-acc-owner@songly.io");
		TestUser troll = user("appeal-acc-troll@songly.io");
		String communityId = createCommunity(owner, "Appeal-uznanie");
		join(troll, communityId);
		ban(owner, communityId, troll);
		appeal(troll, communityId, "obiecuje poprawe");
		mails.clearFor(troll.email());

		mockMvc.perform(delete("/api/v1/communities/" + communityId + "/bans/" + troll.userId()).header("Authorization",
				"Bearer " + owner.token())).andExpect(status().isNoContent());

		assertThat(countOfType(troll, "BAN_APPEAL_ACCEPTED")).isEqualTo(1);
		assertThat(mails.lastTo(troll.email()).subject()).contains("uznane");
		join(troll, communityId);
		assertThat(membershipStatus(troll, communityId).get("status").asText()).isEqualTo("MEMBER");
	}

	/**
	 * Odrzucenie: ban zostaje, sprawa zamknięta, odwołujący zawiadomiony — a
	 * powiadomienie przechodzi mimo blokady moderatora (MODERATION): nie można
	 * odmówić odpowiedzi na własną petycję.
	 */
	@Test
	void rejectionNotifiesTheAppellantEvenAcrossABlock() throws Exception {
		TestUser owner = user("appeal-rej-owner@songly.io");
		TestUser troll = user("appeal-rej-troll@songly.io");
		String communityId = createCommunity(owner, "Appeal-odrzucenie");
		join(troll, communityId);
		ban(owner, communityId, troll);
		appeal(troll, communityId, "nie zgadzam sie");
		// Troll blokuje właściciela jeszcze przed decyzją.
		mockMvc.perform(put("/api/v1/blocks/" + owner.userId()).header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/communities/" + communityId + "/bans/" + troll.userId() + "/appeal/rejection")
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNoContent());

		assertThat(countOfType(troll, "BAN_APPEAL_REJECTED")).isEqualTo(1);
		assertThat(membershipStatus(troll, communityId).get("ban").get("appealStatus").asText()).isEqualTo("REJECTED");

		// Drugie odrzucenie nie ma czego odrzucić.
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/bans/" + troll.userId() + "/appeal/rejection")
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BAN_APPEAL_NOT_FOUND"));
	}

	/** Odrzucać może moderacja, nie dowolny członek. */
	@Test
	void rejectionRequiresModerator() throws Exception {
		TestUser owner = user("appeal-perm-owner@songly.io");
		TestUser troll = user("appeal-perm-troll@songly.io");
		TestUser bystander = user("appeal-perm-bystander@songly.io");
		String communityId = createCommunity(owner, "Appeal-uprawnienia");
		join(troll, communityId);
		join(bystander, communityId);
		ban(owner, communityId, troll);
		appeal(troll, communityId, "prosze o druga szanse");

		mockMvc.perform(post("/api/v1/communities/" + communityId + "/bans/" + troll.userId() + "/appeal/rejection")
				.header("Authorization", "Bearer " + bystander.token())).andExpect(status().isForbidden());
	}

	/**
	 * Nowy ban po wygaśnięciu poprzedniego czyści odwołanie — nowa sprawa daje
	 * świeże prawo do odwołania, nawet po wcześniejszym odrzuceniu.
	 */
	@Test
	void reimposedBanGrantsAFreshAppealRight() throws Exception {
		TestUser owner = user("appeal-fresh-owner@songly.io");
		TestUser troll = user("appeal-fresh-troll@songly.io");
		String communityId = createCommunity(owner, "Appeal-nowasprawa");
		join(troll, communityId);
		ban(owner, communityId, troll);
		appeal(troll, communityId, "pierwsza sprawa");
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/bans/" + troll.userId() + "/appeal/rejection")
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNoContent());
		OffsetDateTime past = Instant.now().minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
		jdbcTemplate.update("UPDATE community_bans SET expires_at = ? WHERE community_id = ? AND user_id = ?", past,
				UUID.fromString(communityId), UUID.fromString(troll.userId()));

		ban(owner, communityId, troll);

		assertThat(membershipStatus(troll, communityId).get("ban").get("appealStatus").isNull()).isTrue();
		appeal(troll, communityId, "druga sprawa");
		assertThat(countOfType(owner, "BAN_APPEAL_SUBMITTED")).isEqualTo(2);
	}

	@Test
	void blankAppealTextIsRejected() throws Exception {
		TestUser owner = user("appeal-blank-owner@songly.io");
		TestUser troll = user("appeal-blank-troll@songly.io");
		String communityId = createCommunity(owner, "Appeal-walidacja");
		join(troll, communityId);
		ban(owner, communityId, troll);

		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"   "}""")).andExpect(status().isBadRequest());
	}
}
