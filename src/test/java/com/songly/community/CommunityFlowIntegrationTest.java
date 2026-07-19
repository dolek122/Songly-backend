package com.songly.community;

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
class CommunityFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String registerAndGetAccessToken(String email) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
	}

	private String createCommunity(String accessToken, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/communities").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"name":"%s","description":"opis"}""".formatted(name))).andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void communityLifecycle_createJoinListLeaveDelete() throws Exception {
		String owner = registerAndGetAccessToken("community-owner@songly.io");
		String member = registerAndGetAccessToken("community-member@songly.io");

		String communityId = createCommunity(owner, "Fani Metalu");

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + member)).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + member))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Fani Metalu"))
				.andExpect(jsonPath("$.memberCount").value(2)).andExpect(jsonPath("$.myRole").value("MEMBER"));

		mockMvc.perform(get("/api/v1/communities/" + communityId + "/members").header("Authorization",
				"Bearer " + owner)).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].role").value("OWNER"))
				.andExpect(jsonPath("$.content[0].displayName").value("community-owner"));

		mockMvc.perform(get("/api/v1/communities/mine").header("Authorization", "Bearer " + member))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(communityId)).exists());

		mockMvc.perform(delete("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + member)).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + member))
				.andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(1))
				.andExpect(jsonPath("$.myRole").doesNotExist());

		mockMvc.perform(delete("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + owner))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + owner))
				.andExpect(status().isNotFound());
	}

	@Test
	void communityManagement_ownerOnly_andUniqueNames() throws Exception {
		String owner = registerAndGetAccessToken("community-rules-owner@songly.io");
		String other = registerAndGetAccessToken("community-rules-other@songly.io");

		String communityId = createCommunity(owner, "Jazzowa Piwnica");

		mockMvc.perform(post("/api/v1/communities").header("Authorization", "Bearer " + other)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"name":"jazzowa piwnica","description":null}""")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMUNITY_NAME_TAKEN"));

		mockMvc.perform(put("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + other)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"name":"Przejęta","description":null}""")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_COMMUNITY_OWNER"));

		mockMvc.perform(put("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + owner)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"name":"Jazzowa Piwnica","description":"nowy opis"}""")).andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("nowy opis"));

		mockMvc.perform(delete("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + owner)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("OWNER_CANNOT_LEAVE"));

		mockMvc.perform(delete("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + other))
				.andExpect(status().isForbidden());
	}

	@Test
	void communityEndpoints_requireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/communities")).andExpect(status().isUnauthorized());
	}
}
