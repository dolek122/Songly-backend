package com.songly.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class BanAppealEscalationIntegrationTest extends AbstractIntegrationTest {

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

	private void appeal(TestUser banned, String communityId, String text) throws Exception {
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal")
				.header("Authorization", "Bearer " + banned.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"%s"}""".formatted(text))).andExpect(status().isNoContent());
	}

	private void rejectAppeal(TestUser moderator, String communityId, TestUser target) throws Exception {
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/bans/" + target.userId() + "/appeal/rejection")
				.header("Authorization", "Bearer " + moderator.token())).andExpect(status().isNoContent());
	}

	private void escalate(TestUser banned, String communityId) throws Exception {
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal/escalation").header("Authorization",
				"Bearer " + banned.token())).andExpect(status().isNoContent());
	}

	private JsonNode queueEntryFor(TestUser adminUser, TestUser subject) throws Exception {
		for (JsonNode entry : escalationQueue(adminUser)) {
			if (subject.userId().equals(entry.get("userId").asText())) {
				return entry;
			}
		}
		return null;
	}

	private JsonNode escalationQueue(TestUser adminUser) throws Exception {
		return objectMapper.readTree(
				mockMvc.perform(get("/api/v1/admin/ban-appeals").header("Authorization", "Bearer " + adminUser.token()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode ownStatus(TestUser user, String communityId) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/membership").header("Authorization",
						"Bearer " + user.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private List<String> logActions(TestUser adminUser, TestUser subject) throws Exception {
		JsonNode content = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/admin/moderation-log?userId=" + subject.userId()).header("Authorization",
						"Bearer " + adminUser.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
		List<String> actions = new ArrayList<>();
		for (JsonNode entry : content) {
			actions.add(entry.get("action").asText());
		}
		return actions;
	}

	@Test
	void escalationReachesAdminsWithBothSidesOfTheCase() throws Exception {
		TestUser adminUser = admin("escal-flow-admin@songly.io");
		TestUser owner = user("escal-flow-owner@songly.io");
		TestUser troll = user("escal-flow-troll@songly.io");
		String communityId = createCommunity(owner, "Escal-przeplyw");
		join(troll, communityId);
		ban(owner, communityId, troll);
		appeal(troll, communityId, "to nie bylem ja");
		rejectAppeal(owner, communityId, troll);
		mails.clear();

		escalate(troll, communityId);

		assertThat(mails.lastTo(adminUser.email()).subject()).contains("administracji");
		assertThat(mails.lastTo(adminUser.email()).body()).doesNotContain("to nie bylem ja");

		JsonNode entry = queueEntryFor(adminUser, troll);
		assertThat(entry).isNotNull();
		assertThat(entry.get("communityId").asText()).isEqualTo(communityId);
		assertThat(entry.get("communityName").asText()).isEqualTo("Escal-przeplyw");
		assertThat(entry.get("userId").asText()).isEqualTo(troll.userId());
		assertThat(entry.get("reason").asText()).isEqualTo("spam");
		assertThat(entry.get("appealText").asText()).isEqualTo("to nie bylem ja");
		assertThat(entry.get("escalatedAt").isNull()).isFalse();

		assertThat(ownStatus(troll, communityId).get("ban").get("appealStatus").asText()).isEqualTo("ESCALATED");
		assertThat(logActions(adminUser, troll)).contains("COMMUNITY_BAN_APPEAL_ESCALATED");
	}

	@Test
	void adminCanOverturnTheBanAndBothSidesAreTold() throws Exception {
		TestUser adminUser = admin("escal-over-admin@songly.io");
		TestUser owner = user("escal-over-owner@songly.io");
		TestUser troll = user("escal-over-troll@songly.io");
		String communityId = createCommunity(owner, "Escal-uchylenie");
		join(troll, communityId);
		ban(owner, communityId, troll);
		appeal(troll, communityId, "prosze o ponowna ocene");
		rejectAppeal(owner, communityId, troll);
		escalate(troll, communityId);
		mails.clear();

		mockMvc.perform(delete("/api/v1/admin/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + adminUser.token())).andExpect(status().isNoContent());

		join(troll, communityId);
		assertThat(ownStatus(troll, communityId).get("status").asText()).isEqualTo("MEMBER");
		assertThat(mails.lastTo(troll.email()).subject()).contains("uchyliła");
		assertThat(mails.lastTo(owner.email()).subject()).contains("uchyliła");
		assertThat(queueEntryFor(adminUser, troll)).isNull();
		assertThat(logActions(adminUser, troll)).contains("COMMUNITY_BAN_ESCALATION_ACCEPTED", "COMMUNITY_BAN_LIFTED");
	}

	@Test
	void adminCanRejectTheEscalationAndThatIsFinal() throws Exception {
		TestUser adminUser = admin("escal-rej-admin@songly.io");
		TestUser owner = user("escal-rej-owner@songly.io");
		TestUser troll = user("escal-rej-troll@songly.io");
		String communityId = createCommunity(owner, "Escal-odrzucenie");
		join(troll, communityId);
		ban(owner, communityId, troll);
		appeal(troll, communityId, "nie zgadzam sie");
		rejectAppeal(owner, communityId, troll);
		escalate(troll, communityId);
		mails.clearFor(troll.email());

		mockMvc.perform(
				post("/api/v1/admin/communities/" + communityId + "/bans/" + troll.userId() + "/escalation-rejection")
						.header("Authorization", "Bearer " + adminUser.token()))
				.andExpect(status().isNoContent());

		assertThat(mails.lastTo(troll.email()).subject()).contains("utrzymała");
		assertThat(ownStatus(troll, communityId).get("ban").get("appealStatus").asText())
				.isEqualTo("ESCALATION_REJECTED");
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + troll.token())).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal/escalation").header("Authorization",
				"Bearer " + troll.token())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BAN_APPEAL_NOT_ESCALATABLE"));
		assertThat(queueEntryFor(adminUser, troll)).isNull();
	}

	@Test
	void escalationRequiresARejectedAppealFirst() throws Exception {
		TestUser owner = user("escal-order-owner@songly.io");
		TestUser troll = user("escal-order-troll@songly.io");
		String communityId = createCommunity(owner, "Escal-kolejnosc");
		join(troll, communityId);
		ban(owner, communityId, troll);

		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal/escalation").header("Authorization",
				"Bearer " + troll.token())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BAN_APPEAL_NOT_ESCALATABLE"));

		appeal(troll, communityId, "czekam");
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal/escalation").header("Authorization",
				"Bearer " + troll.token())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BAN_APPEAL_NOT_ESCALATABLE"));
	}

	@Test
	void adminCannotTouchBansWithoutEscalation() throws Exception {
		TestUser adminUser = admin("escal-auton-admin@songly.io");
		TestUser owner = user("escal-auton-owner@songly.io");
		TestUser troll = user("escal-auton-troll@songly.io");
		String communityId = createCommunity(owner, "Escal-autonomia");
		join(troll, communityId);
		ban(owner, communityId, troll);

		mockMvc.perform(delete("/api/v1/admin/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + adminUser.token())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ESCALATED_APPEAL_NOT_FOUND"));

		appeal(troll, communityId, "czekam");
		mockMvc.perform(delete("/api/v1/admin/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + adminUser.token())).andExpect(status().isNotFound());
	}

	@Test
	void escalationQueueAndDecisionsRequireAdmin() throws Exception {
		TestUser owner = user("escal-perm-owner@songly.io");
		TestUser troll = user("escal-perm-troll@songly.io");
		String communityId = createCommunity(owner, "Escal-uprawnienia");
		join(troll, communityId);
		ban(owner, communityId, troll);
		appeal(troll, communityId, "prosze");
		rejectAppeal(owner, communityId, troll);
		escalate(troll, communityId);

		mockMvc.perform(get("/api/v1/admin/ban-appeals").header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete("/api/v1/admin/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isForbidden());
		mockMvc.perform(
				post("/api/v1/admin/communities/" + communityId + "/bans/" + troll.userId() + "/escalation-rejection")
						.header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isForbidden());
	}
}
