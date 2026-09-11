package com.songly.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Własny status w społeczności (ADR 0015) — w tym jedyne miejsce, w którym
 * zbanowany widzi powód i termin swojego bana. Do tej pory powód czytali
 * wyłącznie moderatorzy (lista banów), a zbanowany dostawał sam fakt.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class MembershipStatusIntegrationTest extends AbstractIntegrationTest {

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

	private JsonNode membershipStatus(TestUser user, String communityId) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/membership").header("Authorization",
						"Bearer " + user.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	@Test
	void ownerSeesTheirRoleAndJoinDate() throws Exception {
		TestUser owner = user("memstatus-owner@songly.io");
		String communityId = createCommunity(owner, "Memstatus-wlasciciel");

		JsonNode status = membershipStatus(owner, communityId);

		assertThat(status.get("status").asText()).isEqualTo("MEMBER");
		assertThat(status.get("role").asText()).isEqualTo("OWNER");
		assertThat(status.get("joinedAt").isNull()).isFalse();
		assertThat(status.get("ban").isNull()).isTrue();
	}

	/**
	 * Sedno ADR 0015: zbanowany widzi powód i termin. Wcześniej powód czytali
	 * wyłącznie moderatorzy, a decyzja docierała do adresata bez uzasadnienia —
	 * czyli nie dało się jej ani zrozumieć, ani zakwestionować.
	 */
	@Test
	void bannedUserSeesReasonAndTerm() throws Exception {
		TestUser owner = user("memstatus-ban-owner@songly.io");
		TestUser troll = user("memstatus-ban-troll@songly.io");
		String communityId = createCommunity(owner, "Memstatus-ban");
		join(troll, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam w kazdym watku","durationDays":7}""")).andExpect(status().isNoContent());

		JsonNode status = membershipStatus(troll, communityId);

		assertThat(status.get("status").asText()).isEqualTo("BANNED");
		assertThat(status.get("role").isNull()).isTrue();
		JsonNode ban = status.get("ban");
		assertThat(ban.get("reason").asText()).isEqualTo("spam w kazdym watku");
		assertThat(ban.get("createdAt").isNull()).isFalse();
		assertThat(ban.get("expiresAt").isNull()).isFalse();
	}

	/**
	 * Ban bez powodu i bezterminowy: status jest, pola są nullami — nie 500-tką.
	 */
	@Test
	void banWithoutReasonOrTermReadsAsBannedWithNulls() throws Exception {
		TestUser owner = user("memstatus-noreason-owner@songly.io");
		TestUser troll = user("memstatus-noreason-troll@songly.io");
		String communityId = createCommunity(owner, "Memstatus-bezpowodu");
		join(troll, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId()).header("Authorization",
				"Bearer " + owner.token())).andExpect(status().isNoContent());

		JsonNode ban = membershipStatus(troll, communityId).get("ban");

		assertThat(ban.get("reason").isNull()).isTrue();
		assertThat(ban.get("expiresAt").isNull()).isTrue();
	}

	@Test
	void outsiderSeesNone() throws Exception {
		TestUser owner = user("memstatus-none-owner@songly.io");
		TestUser outsider = user("memstatus-none-outsider@songly.io");
		String communityId = createCommunity(owner, "Memstatus-obcy");

		JsonNode status = membershipStatus(outsider, communityId);

		assertThat(status.get("status").asText()).isEqualTo("NONE");
		assertThat(status.get("role").isNull()).isTrue();
		assertThat(status.get("ban").isNull()).isTrue();
	}

	/**
	 * Wygasły ban czyta się jak NONE — spójnie z „wygasły ban nie liczy się
	 * nigdzie" (ADR 0014). Pokazywanie go dalej jako BANNED myliłoby klienta,
	 * któremu dołączenie właśnie zaczęło przechodzić.
	 */
	@Test
	void expiredBanReadsAsNone() throws Exception {
		TestUser owner = user("memstatus-exp-owner@songly.io");
		TestUser troll = user("memstatus-exp-troll@songly.io");
		String communityId = createCommunity(owner, "Memstatus-wygasly");
		join(troll, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam","durationDays":1}""")).andExpect(status().isNoContent());
		OffsetDateTime past = Instant.now().minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
		jdbcTemplate.update("UPDATE community_bans SET expires_at = ? WHERE community_id = ? AND user_id = ?", past,
				UUID.fromString(communityId), UUID.fromString(troll.userId()));

		JsonNode status = membershipStatus(troll, communityId);

		assertThat(status.get("status").asText()).isEqualTo("NONE");
		assertThat(status.get("ban").isNull()).isTrue();

		// ...a po powrocie opisuje członkostwo, mimo że wygasły wiersz banu leży
		// w tabeli dalej.
		join(troll, communityId);
		assertThat(membershipStatus(troll, communityId).get("status").asText()).isEqualTo("MEMBER");
	}

	@Test
	void unknownCommunityIsNotFound() throws Exception {
		TestUser someone = user("memstatus-404@songly.io");

		mockMvc.perform(get("/api/v1/communities/" + UUID.randomUUID() + "/membership").header("Authorization",
				"Bearer " + someone.token())).andExpect(status().isNotFound());
	}
}
