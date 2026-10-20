package com.songly.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Zdjęcia w postach (ADR 0010) — wgranie w osobnym kroku, dołączenie kluczem
 * przy publikacji.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class PostImageIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private static byte[] pngBytes() {
		return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 'z', 'd', 'j'};
	}

	private String accessToken(String email) throws Exception {
		return objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	private JsonNode uploadImage(String token) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(multipart("/api/v1/posts/image")
						.file(new MockMultipartFile("file", "zdjecie.png", MediaType.IMAGE_PNG_VALUE, pngBytes()))
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode createPost(String token, String content, String imageKey, int expectedStatus) throws Exception {
		String body = imageKey == null ? """
				{"content":"%s"}""".formatted(content) : """
				{"content":"%s","imageKey":"%s"}""".formatted(content, imageKey);
		String response = mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
	}

	private HttpResponse<byte[]> fetchAnonymously(String url) throws IOException, InterruptedException {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
		}
	}

	@Test
	void uploadedImage_isAttachedToThePostAndPubliclyFetchable() throws Exception {
		String token = accessToken("post-image-happy@songly.io");
		String key = uploadImage(token).get("key").asText();

		JsonNode created = createPost(token, "Nowy album", key, 201);

		String imageUrl = created.get("imageUrl").asText();
		assertThat(fetchAnonymously(imageUrl).body()).isEqualTo(pngBytes());
		mockMvc.perform(get("/api/v1/posts/" + created.get("id").asText()).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(imageUrl));
	}

	@Test
	void postWithoutImage_hasNullImageUrl() throws Exception {
		String token = accessToken("post-image-none@songly.io");

		assertThat(createPost(token, "Bez zdjecia", null, 201).get("imageUrl").isNull()).isTrue();
	}

	/**
	 * Sedno walidacji własności: cudzy klucz nie da się przypiąć do swojego posta.
	 * Bez tego wystarczyłoby podać czyjś klucz, żeby pokazać jego zdjęcie pod swoim
	 * nazwiskiem — a przy usunięciu takiego posta skasować właścicielowi plik.
	 */
	@Test
	void anotherUsersImageKey_isRejected() throws Exception {
		String owner = accessToken("post-image-owner@songly.io");
		String thief = accessToken("post-image-thief@songly.io");
		String foreignKey = uploadImage(owner).get("key").asText();

		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + thief)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"nie moje","imageKey":"%s"}""".formatted(foreignKey)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_POST_IMAGE"));
	}

	/** Zmyślony klucz odpada tak samo jak cudzy — ten sam kod, ta sama odmowa. */
	@Test
	void fabricatedImageKey_isRejected() throws Exception {
		String token = accessToken("post-image-fake@songly.io");

		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"zmyslone","imageKey":"posts/../../etc/passwd"}"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_POST_IMAGE"));
	}

	/**
	 * Własny prefiks to za mało: {@code ..} za nim prowadzi do cudzego pliku, bo
	 * klient HTTP rozwija takie segmenty w adresie, zanim wyśle żądanie. Klucz musi
	 * mieć dokładnie kształt wydawany przy wgraniu.
	 */
	@Test
	void ownPrefixWithTraversal_isRejected() throws Exception {
		String owner = accessToken("post-image-trav-owner@songly.io");
		String thief = accessToken("post-image-trav-thief@songly.io");
		String foreignKey = uploadImage(owner).get("key").asText();
		String thiefId = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + thief))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText();

		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + thief)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"nie moje","imageKey":"posts/%s/../../%s"}""".formatted(thiefId, foreignKey)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_POST_IMAGE"));
	}

	/**
	 * Usunięcie posta kasuje jego zdjęcie. Bez tego każdy skasowany post
	 * zostawiałby plik, którego nic już nie wskazuje i po którym nie ma śladu w
	 * bazie — czyli nie da się go później odnaleźć nawet ręcznie.
	 */
	@Test
	void deletingPost_removesItsImage() throws Exception {
		String token = accessToken("post-image-delete@songly.io");
		JsonNode created = createPost(token, "Do skasowania", uploadImage(token).get("key").asText(), 201);
		String imageUrl = created.get("imageUrl").asText();
		assertThat(fetchAnonymously(imageUrl).statusCode()).isEqualTo(200);

		mockMvc.perform(
				delete("/api/v1/posts/" + created.get("id").asText()).header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());

		assertThat(fetchAnonymously(imageUrl).statusCode()).isEqualTo(404);
	}

	/**
	 * Kasowanie konta sprząta też pliki. Posty giną wsadowo, więc klucze trzeba
	 * odczytać wcześniej — inaczej po skasowaniu wierszy nie ma już skąd ich wziąć.
	 */
	@Test
	void deletingAccount_removesPostImages() throws Exception {
		String token = accessToken("post-image-account@songly.io");
		String imageUrl = createPost(token, "Zniknie z kontem", uploadImage(token).get("key").asText(), 201)
				.get("imageUrl").asText();

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"password123"}""")).andExpect(status().isNoContent());

		assertThat(fetchAnonymously(imageUrl).statusCode()).isEqualTo(404);
	}

	@Test
	void nonImageUploadIsRejected() throws Exception {
		String token = accessToken("post-image-notimage@songly.io");

		mockMvc.perform(multipart("/api/v1/posts/image")
				.file(new MockMultipartFile("file", "plik.png", MediaType.IMAGE_PNG_VALUE, "zwykly tekst".getBytes()))
				.header("Authorization", "Bearer " + token)).andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE"));
	}
}
