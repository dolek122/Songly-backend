package com.songly.notification;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class NotificationFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record TestUser(String accessToken, String userId) {
	}

	private TestUser registerUser(String email) throws Exception {
		MvcResult registerResult = registerAndVerify(email, "password123");
		String accessToken = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("accessToken")
				.asText();
		MvcResult profileResult = mockMvc
				.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		String userId = objectMapper.readTree(profileResult.getResponse().getContentAsString()).get("userId").asText();
		return new TestUser(accessToken, userId);
	}

	@Test
	void socialAndContentActivity_producesNotifications_withReadFlow() throws Exception {
		TestUser ada = registerUser("notif-ada@songly.io");
		TestUser bono = registerUser("notif-bono@songly.io");

		// zaproszenie: powiadomienie dla adresata
		MvcResult requestResult = mockMvc
				.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + ada.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"addresseeId":"%s"}""".formatted(bono.userId())))
				.andExpect(status().isCreated()).andReturn();
		String requestId = objectMapper.readTree(requestResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(
				get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + bono.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + bono.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].type").value("FRIEND_REQUEST_RECEIVED"))
				.andExpect(jsonPath("$.content[0].actorId").value(ada.userId()))
				.andExpect(jsonPath("$.content[0].actorDisplayName").value("notif-ada"));

		// akceptacja: powiadomienie dla wysyłającego
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].type").value("FRIEND_REQUEST_ACCEPTED"))
				.andExpect(jsonPath("$.content[0].actorId").value(bono.userId()));

		// lajk i komentarz: powiadomienia dla autora posta
		MvcResult postResult = mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + ada.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"nowy album!"}"""))
				.andExpect(status().isCreated()).andReturn();
		String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(
				put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + bono.accessToken()))
				.andExpect(status().isNoContent());
		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + bono.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"świetne"}"""))
				.andExpect(status().isCreated());

		// własny lajk nie generuje powiadomienia
		mockMvc.perform(
				put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isNoContent());

		mockMvc.perform(
				get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));

		// oznaczanie jako przeczytane
		MvcResult listResult = mockMvc
				.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andReturn();
		String firstId = objectMapper.readTree(listResult.getResponse().getContentAsString()).get("content").get(0)
				.get("id").asText();

		mockMvc.perform(post("/api/v1/notifications/" + firstId + "/read").header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/notifications/" + firstId + "/read").header("Authorization",
				"Bearer " + ada.accessToken())).andExpect(status().isNoContent());
		mockMvc.perform(
				get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2));

		mockMvc.perform(post("/api/v1/notifications/read-all").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isNoContent());
		mockMvc.perform(
				get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
	}

	@Test
	void joiningCommunity_notifiesOwner() throws Exception {
		TestUser owner = registerUser("notif-community-owner@songly.io");
		TestUser joiner = registerUser("notif-community-joiner@songly.io");

		MvcResult communityResult = mockMvc
				.perform(post("/api/v1/communities").header("Authorization", "Bearer " + owner.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"name":"Metalheads","description":"dla fanów metalu"}"""))
				.andExpect(status().isCreated()).andReturn();
		String communityId = objectMapper.readTree(communityResult.getResponse().getContentAsString()).get("id")
				.asText();

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + joiner.accessToken())).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + owner.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].type").value("COMMUNITY_MEMBER_JOINED"))
				.andExpect(jsonPath("$.content[0].actorId").value(joiner.userId()));

		// ponowne dołączenie (idempotentne) nie generuje kolejnego powiadomienia
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + joiner.accessToken())).andExpect(status().isNoContent());
		mockMvc.perform(
				get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + owner.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
	}

	@Test
	void sharingPost_notifiesAuthor() throws Exception {
		TestUser author = registerUser("notif-share-author@songly.io");
		TestUser sharer = registerUser("notif-share-sharer@songly.io");

		MvcResult postResult = mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"do udostępnienia"}"""))
				.andExpect(status().isCreated()).andReturn();
		String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(
				put("/api/v1/posts/" + postId + "/shares").header("Authorization", "Bearer " + sharer.accessToken()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + author.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].type").value("POST_SHARED"))
				.andExpect(jsonPath("$.content[0].actorId").value(sharer.userId()));
	}

	/**
	 * ADR 0035: pętla cofnij/przywróć na lajku, udostępnieniu i członkostwie
	 * zostawia w skrzynce adresata po jednym powiadomieniu — także gdy adresat
	 * zdążył je przeczytać.
	 */
	@Test
	void undoRedoLoops_leaveOneNotificationPerActorAndResource() throws Exception {
		TestUser author = registerUser("notif-loop-author@songly.io");
		TestUser looper = registerUser("notif-loop-looper@songly.io");

		MvcResult postResult = mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"pętla"}"""))
				.andExpect(status().isCreated()).andReturn();
		String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();
		MvcResult communityResult = mockMvc
				.perform(post("/api/v1/communities").header("Authorization", "Bearer " + author.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"name":"Pętla","description":"dedup"}"""))
				.andExpect(status().isCreated()).andReturn();
		String communityId = objectMapper.readTree(communityResult.getResponse().getContentAsString()).get("id")
				.asText();

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(
					put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + looper.accessToken()))
					.andExpect(status().isNoContent());
			mockMvc.perform(delete("/api/v1/posts/" + postId + "/likes").header("Authorization",
					"Bearer " + looper.accessToken())).andExpect(status().isNoContent());
			mockMvc.perform(put("/api/v1/posts/" + postId + "/shares").header("Authorization",
					"Bearer " + looper.accessToken())).andExpect(status().isNoContent());
			mockMvc.perform(delete("/api/v1/posts/" + postId + "/shares").header("Authorization",
					"Bearer " + looper.accessToken())).andExpect(status().isNoContent());
			mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
					"Bearer " + looper.accessToken())).andExpect(status().isNoContent());
			mockMvc.perform(delete("/api/v1/communities/" + communityId + "/membership").header("Authorization",
					"Bearer " + looper.accessToken())).andExpect(status().isNoContent());
			// przeczytanie nie otwiera furtki na kolejne powiadomienie
			mockMvc.perform(
					post("/api/v1/notifications/read-all").header("Authorization", "Bearer " + author.accessToken()))
					.andExpect(status().isNoContent());
		}

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + author.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(3))
				.andExpect(jsonPath("$.content[?(@.type == 'POST_LIKED')]", hasSize(1)))
				.andExpect(jsonPath("$.content[?(@.type == 'POST_SHARED')]", hasSize(1)))
				.andExpect(jsonPath("$.content[?(@.type == 'COMMUNITY_MEMBER_JOINED')]", hasSize(1)));
	}

	/**
	 * Zaproszenia zwijają się do jednego tylko do przeczytania (ADR 0035): pętla
	 * wyślij/anuluj daje jedno nieprzeczytane wskazujące ostatnie zaproszenie, a
	 * nowe zaproszenie po przeczytaniu jest nową wiadomością.
	 */
	@Test
	void friendRequestLoop_collapsesWhileUnread_andPointsAtPendingRequest() throws Exception {
		TestUser addressee = registerUser("notif-req-addressee@songly.io");
		TestUser looper = registerUser("notif-req-looper@songly.io");

		String lastRequestId = null;
		for (int i = 0; i < 3; i++) {
			if (lastRequestId != null) {
				mockMvc.perform(delete("/api/v1/friends/requests/" + lastRequestId).header("Authorization",
						"Bearer " + looper.accessToken())).andExpect(status().isNoContent());
			}
			lastRequestId = sendFriendRequest(looper, addressee);
		}

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + addressee.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].type").value("FRIEND_REQUEST_RECEIVED"))
				.andExpect(jsonPath("$.content[0].resourceId").value(lastRequestId))
				.andExpect(jsonPath("$.content[0].readAt").doesNotExist());

		mockMvc.perform(
				post("/api/v1/notifications/read-all").header("Authorization", "Bearer " + addressee.accessToken()))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/v1/friends/requests/" + lastRequestId).header("Authorization",
				"Bearer " + looper.accessToken())).andExpect(status().isNoContent());
		String resentRequestId = sendFriendRequest(looper, addressee);

		mockMvc.perform(
				get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + addressee.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + addressee.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].resourceId").value(resentRequestId));
	}

	private String sendFriendRequest(TestUser from, TestUser to) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + from.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"addresseeId":"%s"}""".formatted(to.userId())))
				.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void notificationEndpoints_requireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
	}
}
