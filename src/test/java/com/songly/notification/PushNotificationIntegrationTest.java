package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import com.songly.common.push.PushMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kanał push (ADR 0040): kto dostaje push, co w nim jest i kiedy urządzenie
 * przestaje go dostawać. Tokeny są unikalne per przypadek — baza i rekorder są
 * wspólne dla całej suity.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class PushNotificationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PushDeviceRepository pushDeviceRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private record TestUser(String token, String userId, String displayName) {
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		JsonNode profile = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		return new TestUser(token, profile.get("userId").asText(), profile.get("displayName").asText());
	}

	private void registerDevice(TestUser user, String deviceToken) throws Exception {
		mockMvc.perform(put("/api/v1/notifications/push-devices").header("Authorization", "Bearer " + user.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"token":"%s","platform":"ANDROID"}""".formatted(deviceToken)))
				.andExpect(status().isNoContent());
	}

	private String createPost(TestUser author) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"post do pushy"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	private void like(TestUser liker, String postId) throws Exception {
		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + liker.token()))
				.andExpect(status().isNoContent());
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

	@Test
	void likeSendsPushNamingTheActorButNothingMore() throws Exception {
		TestUser author = user("push-like-author@songly.io");
		TestUser fan = user("push-like-fan@songly.io");
		registerDevice(author, "push-like-device");
		String postId = createPost(author);

		like(fan, postId);

		List<PushMessage> sent = pushes.sentTo("push-like-device");
		assertThat(sent).hasSize(1);
		PushMessage push = sent.getFirst();
		assertThat(push.title()).isEqualTo(fan.displayName());
		assertThat(push.body()).isEqualTo("Polubienie Twojego posta");
		assertThat(push.data()).containsEntry("type", "POST_LIKED").containsEntry("resourceId", postId)
				.containsKey("notificationId");
	}

	/**
	 * Zawieszenie nie odpina telefonów, a zawieszone konto nie ma dostępu do
	 * niczego, do czego push prowadzi (ADR 0022). Bez filtra ekran blokady dalej
	 * pokazywał nazwy osób zapraszających je do znajomych (ADR 0049).
	 */
	@Test
	void suspendedRecipientGetsNoPush() throws Exception {
		TestUser suspended = user("push-suspended-recipient@songly.io");
		TestUser sender = user("push-suspended-sender@songly.io");
		registerDevice(suspended, "push-suspended-device");
		jdbcTemplate.update("UPDATE users SET suspended_at = now(), suspension_reason = 'test' WHERE id = ?::uuid",
				suspended.userId());

		mockMvc.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + sender.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"addresseeId":"%s"}""".formatted(suspended.userId()))).andExpect(status().isCreated());

		assertThat(pushes.sentTo("push-suspended-device")).isEmpty();
	}

	/**
	 * Deduplikacja ze skrzynki (ADR 0035) obowiązuje też push: pętla
	 * cofnij/przywróć nie może dzwonić komuś w kieszeni przy każdym obrocie.
	 */
	@Test
	void likeLoopDoesNotRepeatThePush() throws Exception {
		TestUser author = user("push-loop-author@songly.io");
		TestUser fan = user("push-loop-fan@songly.io");
		registerDevice(author, "push-loop-device");
		String postId = createPost(author);

		for (int round = 0; round < 3; round++) {
			like(fan, postId);
			mockMvc.perform(
					delete("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + fan.token()))
					.andExpect(status().isNoContent());
		}

		assertThat(pushes.sentTo("push-loop-device")).hasSize(1);
	}

	/**
	 * Filtr blokad działa w chwili wysyłki, ale decyzja moderacyjna przechodzi mimo
	 * blokady — inaczej wystarczyłoby zablokować moderatora, żeby nie dowiedzieć
	 * się o własnym banie (ADR 0012). I nie niesie nazwy moderatora.
	 */
	@Test
	void blockSilencesActivityButNotModerationDecisions() throws Exception {
		TestUser owner = user("push-block-owner@songly.io");
		TestUser troll = user("push-block-troll@songly.io");
		registerDevice(owner, "push-block-owner-device");
		registerDevice(troll, "push-block-troll-device");
		String communityId = createCommunity(owner, "Push-blokady");
		mockMvc.perform(put("/api/v1/blocks/" + troll.userId()).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + troll.token())).andExpect(status().isNoContent());
		assertThat(pushes.sentTo("push-block-owner-device")).isEmpty();

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam"}""")).andExpect(status().isNoContent());
		List<PushMessage> toTroll = pushes.sentTo("push-block-troll-device");
		assertThat(toTroll).hasSize(1);
		assertThat(toTroll.getFirst().title()).isEqualTo("„Push-blokady”");
		assertThat(toTroll.getFirst().title() + toTroll.getFirst().body()).doesNotContain(owner.displayName())
				.doesNotContain("spam");
	}

	/**
	 * Ten sam telefon, inne konto: poprzedni właściciel nie może tam dalej dostawać
	 * swoich pushy.
	 */
	@Test
	void reRegisteringTokenMovesTheDeviceToTheNewAccount() throws Exception {
		TestUser first = user("push-move-first@songly.io");
		TestUser second = user("push-move-second@songly.io");
		TestUser fan = user("push-move-fan@songly.io");
		registerDevice(first, "push-move-device");
		registerDevice(second, "push-move-device");

		like(fan, createPost(first));

		assertThat(pushes.sentTo("push-move-device")).isEmpty();
	}

	/**
	 * Cudzy token daje 204, ale niczego nie odpina — odpowiedź nie mówi, czyj to
	 * token.
	 */
	@Test
	void removingSomeoneElsesTokenHasNoEffect() throws Exception {
		TestUser owner = user("push-rm-owner@songly.io");
		TestUser stranger = user("push-rm-stranger@songly.io");
		TestUser fan = user("push-rm-fan@songly.io");
		registerDevice(owner, "push-rm-device");

		mockMvc.perform(
				post("/api/v1/notifications/push-devices/removal").header("Authorization", "Bearer " + stranger.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"token":"push-rm-device"}"""))
				.andExpect(status().isNoContent());
		like(fan, createPost(owner));
		assertThat(pushes.sentTo("push-rm-device")).hasSize(1);

		mockMvc.perform(post("/api/v1/notifications/push-devices/removal")
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"token":"push-rm-device"}""")).andExpect(status().isNoContent());
		like(fan, createPost(owner));
		assertThat(pushes.sentTo("push-rm-device")).hasSize(1);
	}

	/**
	 * Zmiana hasła odcina telefon razem z sesjami — inaczej skradziony telefon
	 * dalej by dzwonił.
	 */
	@Test
	void passwordChangeUnregistersAllDevices() throws Exception {
		TestUser owner = user("push-pwd-owner@songly.io");
		registerDevice(owner, "push-pwd-device");

		mockMvc.perform(put("/api/v1/auth/password").header("Authorization", "Bearer " + owner.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"currentPassword":"password123","newPassword":"newPassword456"}"""))
				.andExpect(status().isNoContent());

		assertThat(pushDeviceRepository.findById("push-pwd-device")).isEmpty();
	}

	/**
	 * Urządzenie, którego FCM już nie zna, znika — kolejne pushe nie lecą w
	 * próżnię.
	 */
	@Test
	void unregisteredDeviceIsForgotten() throws Exception {
		TestUser author = user("push-gone-author@songly.io");
		TestUser fan = user("push-gone-fan@songly.io");
		registerDevice(author, "push-gone-device");
		pushes.markUnregistered("push-gone-device");

		like(fan, createPost(author));

		assertThat(pushDeviceRepository.findById("push-gone-device")).isEmpty();
	}
}
