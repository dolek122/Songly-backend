package com.songly.community;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Bany czasowe (ADR 0014). Wygasanie jest leniwe — nic nie dzieje się w chwili
 * wygaśnięcia, więc „upływ czasu" symulujemy, przestawiając {@code expires_at}
 * w przeszłość SQL-em: to samo podejście co w
 * {@code SessionCleanupIntegrationTest}, bo żadna ścieżka API (słusznie) nie
 * pozwala ustawić terminu w przeszłości.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class TemporaryBanIntegrationTest extends AbstractIntegrationTest {

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

	private void ban(TestUser moderator, String communityId, TestUser target, Integer durationDays) throws Exception {
		String body = durationDays == null ? """
				{"reason":"spam"}""" : """
				{"reason":"spam","durationDays":%d}""".formatted(durationDays);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + target.userId())
				.header("Authorization", "Bearer " + moderator.token()).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isNoContent());
	}

	private JsonNode banList(TestUser moderator, String communityId) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/bans").header("Authorization",
						"Bearer " + moderator.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
	}

	/** Przestawia termin banu w przeszłość — symulacja upływu czasu. */
	private void expireBan(String communityId, TestUser target) {
		OffsetDateTime past = Instant.now().minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
		int updated = jdbcTemplate.update(
				"UPDATE community_bans SET expires_at = ? WHERE community_id = ? AND user_id = ?", past,
				UUID.fromString(communityId), UUID.fromString(target.userId()));
		assertThat(updated).isEqualTo(1);
	}

	private long bannedNotificationCount(TestUser user) throws Exception {
		JsonNode content = objectMapper
				.readTree(
						mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + user.token()))
								.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("content");
		long count = 0;
		for (JsonNode node : content) {
			if ("COMMUNITY_BANNED".equals(node.get("type").asText())) {
				count++;
			}
		}
		return count;
	}

	@Test
	void temporaryBanBlocksReturnAndCarriesItsTermToBanListAndMail() throws Exception {
		TestUser owner = user("tempban-block-owner@songly.io");
		TestUser troll = user("tempban-block-troll@songly.io");
		String communityId = createCommunity(owner, "Tempban-blokada");
		join(troll, communityId);
		mails.clearFor(troll.email());

		ban(owner, communityId, troll, 7);

		// Dopóki ban trwa, dołączenie odbija się jak przy bezterminowym.
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + troll.token())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("BANNED_FROM_COMMUNITY"));

		JsonNode ban = banList(owner, communityId).get(0);
		assertThat(ban.get("userId").asText()).isEqualTo(troll.userId());
		assertThat(ban.get("expiresAt").isNull()).isFalse();

		// Zbanowany musi wiedzieć, że to tydzień — ban czasowy, który wygląda na
		// dożywotni, mija się z celem.
		assertThat(mails.lastTo(troll.email()).body()).contains("wygasa za 7 dni");
	}

	@Test
	void expiredBanLetsTheUserBackIn() throws Exception {
		TestUser owner = user("tempban-exp-owner@songly.io");
		TestUser troll = user("tempban-exp-troll@songly.io");
		String communityId = createCommunity(owner, "Tempban-powrot");
		join(troll, communityId);
		ban(owner, communityId, troll, 1);
		expireBan(communityId, troll);

		// Dołączenie przechodzi, pisanie też — wygasły ban nie liczy się nigdzie.
		join(troll, communityId);
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/posts")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"wrocilem"}""")).andExpect(status().isCreated());

		// ...a lista banów go nie pokazuje: wygasły ban to historia, nie stan.
		assertThat(banList(owner, communityId)).isEmpty();
	}

	/**
	 * Ponowny ban po wygaśnięciu to nowa decyzja: nadpisuje wiersz (klucz główny
	 * nie dopuszcza drugiego) i zawiadamia ponownie. Bez tego rozróżnienia reguła
	 * „tylko nowy ban powiadamia" (ADR 0012) wyciszałaby każdy kolejny ban tej
	 * samej osoby na zawsze.
	 */
	@Test
	void banAfterExpiryIsANewBanAndNotifiesAgain() throws Exception {
		TestUser owner = user("tempban-reban-owner@songly.io");
		TestUser troll = user("tempban-reban-troll@songly.io");
		String communityId = createCommunity(owner, "Tempban-recydywa");
		join(troll, communityId);
		ban(owner, communityId, troll, 1);
		expireBan(communityId, troll);
		mails.clearFor(troll.email());

		ban(owner, communityId, troll, null);

		assertThat(bannedNotificationCount(troll)).isEqualTo(2);
		// Wiersz nadpisany w całości: ban jest teraz bezterminowy...
		assertThat(banList(owner, communityId).get(0).get("expiresAt").isNull()).isTrue();
		// ...i mail mówi to wprost.
		assertThat(mails.lastTo(troll.email()).body()).contains("bezterminowy");
	}

	/**
	 * Ban aktywny zostaje nietknięty przy powtórnym banie — zmiana terminu to unban
	 * + ban, nie cicha podmiana przy zduplikowanym kliknięciu.
	 */
	@Test
	void banWhileActiveDoesNotChangeItsTerm() throws Exception {
		TestUser owner = user("tempban-idem-owner@songly.io");
		TestUser troll = user("tempban-idem-troll@songly.io");
		String communityId = createCommunity(owner, "Tempban-idempotencja");
		join(troll, communityId);
		ban(owner, communityId, troll, 7);

		ban(owner, communityId, troll, null);

		assertThat(banList(owner, communityId).get(0).get("expiresAt").isNull()).isFalse();
		assertThat(bannedNotificationCount(troll)).isEqualTo(1);
	}

	@Test
	void nonPositiveDurationIsRejected() throws Exception {
		TestUser owner = user("tempban-valid-owner@songly.io");
		TestUser troll = user("tempban-valid-troll@songly.io");
		String communityId = createCommunity(owner, "Tempban-walidacja");
		join(troll, communityId);

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam","durationDays":0}""")).andExpect(status().isBadRequest());
	}

	/**
	 * Absurdalny termin wypychał {@code expires_at} poza zakres znacznika czasu
	 * Postgresa i kończył się błędem serwera zamiast 400 (ADR 0049).
	 */
	@Test
	void absurdlyLongDurationIsRejected() throws Exception {
		TestUser owner = user("tempban-huge-owner@songly.io");
		TestUser troll = user("tempban-huge-troll@songly.io");
		String communityId = createCommunity(owner, "Tempban-bez-konca");
		join(troll, communityId);

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam","durationDays":2000000000}""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}
}
