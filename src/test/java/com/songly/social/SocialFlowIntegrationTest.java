package com.songly.social;

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
class SocialFlowIntegrationTest extends AbstractIntegrationTest {

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

	private String sendRequest(TestUser from, TestUser to) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + from.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"addresseeId":"%s"}""".formatted(to.userId())))
				.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void friendRequestFlow_sendAcceptListUnfriend() throws Exception {
		TestUser ada = registerUser("social-ada@songly.io");
		TestUser bono = registerUser("social-bono@songly.io");

		String requestId = sendRequest(ada, bono);

		mockMvc.perform(get("/api/v1/friends/requests?direction=INCOMING").header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(requestId))
				.andExpect(jsonPath("$.content[0].requesterDisplayName").value("social-ada"));

		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));

		mockMvc.perform(get("/api/v1/friends").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].userId").value(bono.userId()))
				.andExpect(jsonPath("$.content[0].displayName").value("social-bono"));

		mockMvc.perform(get("/api/v1/friends").header("Authorization", "Bearer " + bono.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].userId").value(ada.userId()));

		mockMvc.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + ada.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"addresseeId":"%s"}""".formatted(bono.userId()))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_FRIENDS"));

		mockMvc.perform(
				delete("/api/v1/friends/" + bono.userId()).header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/friends").header("Authorization", "Bearer " + bono.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
	}

	@Test
	void friendRequest_canBeDeclined_andAcceptOnlyByAddressee() throws Exception {
		TestUser ada = registerUser("social-decline-ada@songly.io");
		TestUser bono = registerUser("social-decline-bono@songly.io");

		String requestId = sendRequest(ada, bono);

		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + ada.accessToken())).andExpect(status().isNotFound());

		mockMvc.perform(post("/api/v1/friends/requests/%s/decline".formatted(requestId)).header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DECLINED"));

		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("FRIEND_REQUEST_ALREADY_HANDLED"));
	}

	@Test
	void mutualPendingRequests_becomeFriendshipInsteadOfDuplicate() throws Exception {
		TestUser ada = registerUser("social-mutual-ada@songly.io");
		TestUser bono = registerUser("social-mutual-bono@songly.io");

		sendRequest(ada, bono);

		mockMvc.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + bono.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"addresseeId":"%s"}""".formatted(ada.userId()))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));

		mockMvc.perform(get("/api/v1/friends").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
	}

	@Test
	void blockingUser_removesRelationAndPreventsNewRequests() throws Exception {
		TestUser ada = registerUser("social-block-ada@songly.io");
		TestUser bono = registerUser("social-block-bono@songly.io");

		String requestId = sendRequest(ada, bono);
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/blocks/" + bono.userId()).header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/friends").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));

		mockMvc.perform(get("/api/v1/blocks").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].userId").value(bono.userId()));

		mockMvc.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + bono.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"addresseeId":"%s"}""".formatted(ada.userId()))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("USER_BLOCKED"));

		mockMvc.perform(
				delete("/api/v1/blocks/" + bono.userId()).header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + bono.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"addresseeId":"%s"}""".formatted(ada.userId()))).andExpect(status().isCreated());
	}

	@Test
	void friendRequest_canBeCancelledByRequester_notByAddressee() throws Exception {
		TestUser ada = registerUser("social-cancel-ada@songly.io");
		TestUser bono = registerUser("social-cancel-bono@songly.io");

		String requestId = sendRequest(ada, bono);

		// Adresat nie może anulować cudzego zaproszenia — tylko nadawca; dla adresata
		// zaproszenie jest "niewidoczne" (404), żeby nie ujawniać relacji.
		mockMvc.perform(
				delete("/api/v1/friends/requests/" + requestId).header("Authorization", "Bearer " + bono.accessToken()))
				.andExpect(status().isNotFound());

		mockMvc.perform(
				delete("/api/v1/friends/requests/" + requestId).header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isNoContent());

		// Po anulowaniu znika z listy wychodzących nadawcy i przychodzących adresata.
		mockMvc.perform(get("/api/v1/friends/requests?direction=OUTGOING").header("Authorization",
				"Bearer " + ada.accessToken())).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0));
		mockMvc.perform(get("/api/v1/friends/requests?direction=INCOMING").header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0));

		// Naprawdę usunięte, nie tylko ukryte — to samo zaproszenie można wysłać od
		// nowa.
		mockMvc.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + ada.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"addresseeId":"%s"}""".formatted(bono.userId()))).andExpect(status().isCreated());
	}

	@Test
	void cancelRequest_returnsConflict_whenAlreadyAccepted() throws Exception {
		TestUser ada = registerUser("social-cancel-accepted-ada@songly.io");
		TestUser bono = registerUser("social-cancel-accepted-bono@songly.io");

		String requestId = sendRequest(ada, bono);
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + bono.accessToken())).andExpect(status().isOk());

		mockMvc.perform(
				delete("/api/v1/friends/requests/" + requestId).header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FRIEND_REQUEST_ALREADY_HANDLED"));
	}

	@Test
	void friendEndpoints_requireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/friends")).andExpect(status().isUnauthorized());
	}
}
