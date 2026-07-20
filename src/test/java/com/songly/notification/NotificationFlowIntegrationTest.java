package com.songly.notification;

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
		MvcResult registerResult = mockMvc
				.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isCreated())
				.andReturn();
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
		MvcResult requestResult = mockMvc.perform(post("/api/v1/friends/requests")
				.header("Authorization", "Bearer " + ada.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"addresseeId":"%s"}""".formatted(bono.userId()))).andExpect(status().isCreated()).andReturn();
		String requestId = objectMapper.readTree(requestResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + bono.accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].type").value("FRIEND_REQUEST_RECEIVED"))
				.andExpect(jsonPath("$.content[0].actorId").value(ada.userId()))
				.andExpect(jsonPath("$.content[0].actorDisplayName").value("notif-ada"));

		// akceptacja: powiadomienie dla wysyłającego
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId))
				.header("Authorization", "Bearer " + bono.accessToken())).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].type").value("FRIEND_REQUEST_ACCEPTED"))
				.andExpect(jsonPath("$.content[0].actorId").value(bono.userId()));

		// lajk i komentarz: powiadomienia dla autora posta
		MvcResult postResult = mockMvc.perform(post("/api/v1/posts")
				.header("Authorization", "Bearer " + ada.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"content":"nowy album!"}""")).andExpect(status().isCreated()).andReturn();
		String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/posts/" + postId + "/comments")
				.header("Authorization", "Bearer " + bono.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"content":"świetne"}""")).andExpect(status().isCreated());

		// własny lajk nie generuje powiadomienia
		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization",
				"Bearer " + ada.accessToken())).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization",
				"Bearer " + ada.accessToken())).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));

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
		mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization",
				"Bearer " + ada.accessToken())).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2));

		mockMvc.perform(post("/api/v1/notifications/read-all").header("Authorization",
				"Bearer " + ada.accessToken())).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization",
				"Bearer " + ada.accessToken())).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
	}

	@Test
	void sharingPost_notifiesAuthor() throws Exception {
		TestUser author = registerUser("notif-share-author@songly.io");
		TestUser sharer = registerUser("notif-share-sharer@songly.io");

		MvcResult postResult = mockMvc.perform(post("/api/v1/posts")
				.header("Authorization", "Bearer " + author.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"content":"do udostępnienia"}""")).andExpect(status().isCreated()).andReturn();
		String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/api/v1/posts/" + postId + "/shares").header("Authorization",
				"Bearer " + sharer.accessToken())).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + author.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].type").value("POST_SHARED"))
				.andExpect(jsonPath("$.content[0].actorId").value(sharer.userId()));
	}

	@Test
	void notificationEndpoints_requireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
	}
}
