package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Powiadomienia o zmianie roli moderatora (ADR 0016). ADR 0012 świadomie je
 * pominął („nowy moderator zobaczy kolejkę, gdy wejdzie do społeczności") — ale
 * odebranie roli objawiało się wyłącznie 403-kami na kolejce, czyli decyzja
 * wyglądała jak awaria.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class ModeratorRoleNotificationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

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

	private void setRole(TestUser owner, String communityId, TestUser target, String role) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/members/" + target.userId() + "/role")
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"role":"%s"}""".formatted(role))).andExpect(status().isNoContent());
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

	@Test
	void grantAndRevokeNotifyTheAffectedMember() throws Exception {
		TestUser owner = user("rolenotif-flow-owner@songly.io");
		TestUser member = user("rolenotif-flow-member@songly.io");
		String communityId = createCommunity(owner, "Rolenotif-przeplyw");
		join(member, communityId);

		setRole(owner, communityId, member, "MODERATOR");
		assertThat(countOfType(member, "COMMUNITY_MODERATOR_GRANTED")).isEqualTo(1);

		setRole(owner, communityId, member, "MEMBER");
		assertThat(countOfType(member, "COMMUNITY_MODERATOR_REVOKED")).isEqualTo(1);
	}

	/**
	 * Ustawienie roli, którą członek już ma, nie jest zmianą — ta sama zasada co
	 * „tylko nowy ban" (ADR 0014): powtórzone kliknięcie właściciela nie dokłada
	 * powiadomień o tym samym.
	 */
	@Test
	void settingTheSameRoleAgainDoesNotNotify() throws Exception {
		TestUser owner = user("rolenotif-idem-owner@songly.io");
		TestUser member = user("rolenotif-idem-member@songly.io");
		String communityId = createCommunity(owner, "Rolenotif-idempotencja");
		join(member, communityId);

		for (int attempt = 0; attempt < 3; attempt++) {
			setRole(owner, communityId, member, "MODERATOR");
		}

		assertThat(countOfType(member, "COMMUNITY_MODERATOR_GRANTED")).isEqualTo(1);
	}

	/**
	 * Zmiana roli to zwykłe powiadomienie, nie MODERATION (ADR 0016): nadaje albo
	 * odbiera uprawnienia, ale niczego nie ogranicza w zwykłym uczestnictwie.
	 * Podlega więc filtrowi blokad — kto zablokował właściciela, nie widzi od niego
	 * także tego — i nie wychodzi mailem.
	 */
	@Test
	void roleChangeIsHiddenByBlockAndSendsNoMail() throws Exception {
		TestUser owner = user("rolenotif-block-owner@songly.io");
		TestUser member = user("rolenotif-block-member@songly.io");
		String communityId = createCommunity(owner, "Rolenotif-blokada");
		join(member, communityId);
		mails.clearFor(member.email());

		setRole(owner, communityId, member, "MODERATOR");

		assertThat(mails.sentTo(member.email())).isEmpty();

		mockMvc.perform(put("/api/v1/blocks/" + owner.userId()).header("Authorization", "Bearer " + member.token()))
				.andExpect(status().isNoContent());

		assertThat(countOfType(member, "COMMUNITY_MODERATOR_GRANTED")).isZero();
	}
}
