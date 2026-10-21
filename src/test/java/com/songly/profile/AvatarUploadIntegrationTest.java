package com.songly.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import com.songly.TestImages;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Upload avatara od końca do końca (ADR 0010).
 *
 * <p>
 * Test integracyjny z prawdziwym MinIO, bo pytanie, na które odpowiada, brzmi
 * „czy plik naprawdę da się potem pobrać publicznym adresem" — a na to atrapa
 * magazynu nie odpowiada, tylko potwierdza samą siebie. Dlatego zwrócony URL
 * pobieramy <b>zwykłym klientem HTTP, bez uwierzytelnienia</b>: dokładnie tak,
 * jak zrobi to przeglądarka albo aplikacja mobilna.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class AvatarUploadIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String accessToken(String email) throws Exception {
		return objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	/**
	 * Prawdziwy, minimalny PNG. Sam nagłówek przestał wystarczać, kiedy backend
	 * zaczął czytać strukturę pliku, żeby usunąć metadane (ADR 0047).
	 */
	private static byte[] pngBytes() {
		return TestImages.png();
	}

	private static byte[] jpegBytes() {
		return TestImages.jpeg();
	}

	private MvcResult uploadAvatar(String token, byte[] content, String filename, String declaredType,
			int expectedStatus) throws Exception {
		return mockMvc.perform(multipart("/api/v1/profiles/me/avatar")
				.file(new MockMultipartFile("file", filename, declaredType, content))
				.header("Authorization", "Bearer " + token)).andExpect(status().is(expectedStatus)).andReturn();
	}

	private String avatarUrlOf(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("avatarUrl").asText();
	}

	/** Pobranie bez żadnego nagłówka autoryzacji — tak jak zrobi to klient. */
	private HttpResponse<byte[]> fetchAnonymously(String url) throws IOException, InterruptedException {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
		}
	}

	/**
	 * Sedno całej zmiany: plik wgrany przez API naprawdę leży w magazynie i
	 * naprawdę da się go pobrać spod zwróconego adresu.
	 */
	@Test
	void uploadedAvatar_isPubliclyFetchableAtTheReturnedUrl() throws Exception {
		String token = accessToken("avatar-happy@songly.io");

		String url = avatarUrlOf(uploadAvatar(token, pngBytes(), "ja.png", MediaType.IMAGE_PNG_VALUE, 200));

		HttpResponse<byte[]> fetched = fetchAnonymously(url);
		assertThat(fetched.statusCode()).isEqualTo(200);
		assertThat(fetched.body()).isEqualTo(pngBytes());
		assertThat(fetched.headers().firstValue("content-type")).contains(MediaType.IMAGE_PNG_VALUE);
	}

	/**
	 * Avatar jest publiczny, a zdjęcie z aparatu niesie w EXIF-ie miejsce, w którym
	 * je zrobiono (ADR 0047). Sprawdzamy plik, który naprawdę leży w magazynie, a
	 * nie to, co zwróciło API.
	 */
	@Test
	void uploadedAvatar_isStoredWithoutCameraMetadata() throws Exception {
		String token = accessToken("avatar-exif@songly.io");

		String url = avatarUrlOf(
				uploadAvatar(token, TestImages.jpegWithExif(6), "z-aparatu.jpg", MediaType.IMAGE_JPEG_VALUE, 200));

		byte[] stored = fetchAnonymously(url).body();
		assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(TestImages.SECRET);
		assertThat(ImageIO.read(new ByteArrayInputStream(stored)).getWidth()).isEqualTo(2);
	}

	/**
	 * Format bierze się z <b>zawartości</b>, nie z nagłówka ani nazwy pliku. Tu oba
	 * kłamią — plik jest JPEG-iem, a klient twierdzi, że to PNG o nazwie
	 * {@code .html}. Rozszerzenie w kluczu musi wyjść z rozpoznania, inaczej dałoby
	 * się zdobyć adres, spod którego serwujemy cudzy HTML na własnej domenie.
	 */
	@Test
	void fileExtensionAndContentTypeFromTheClientAreIgnored() throws Exception {
		String token = accessToken("avatar-liar@songly.io");

		String url = avatarUrlOf(uploadAvatar(token, jpegBytes(), "zlosliwy.html", MediaType.TEXT_HTML_VALUE, 200));

		assertThat(url).endsWith(".jpg");
		assertThat(fetchAnonymously(url).headers().firstValue("content-type")).contains(MediaType.IMAGE_JPEG_VALUE);
	}

	/**
	 * Plik, który obrazem nie jest, odpada — nawet jeśli nagłówek twierdzi inaczej.
	 * To jest jedyny powód, dla którego bajty w ogóle idą przez backend, a nie
	 * prosto do magazynu na podpisanym URL-u.
	 */
	@Test
	void nonImageIsRejected_evenWhenItClaimsToBeOne() throws Exception {
		String token = accessToken("avatar-notimage@songly.io");

		mockMvc.perform(multipart("/api/v1/profiles/me/avatar").file(new MockMultipartFile("file", "malware.png",
				MediaType.IMAGE_PNG_VALUE, "MZ to nie jest obraz".getBytes()))
				.header("Authorization", "Bearer " + token)).andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE"));

		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").doesNotExist());
	}

	@Test
	void emptyFileIsRejectedWithItsOwnCode() throws Exception {
		String token = accessToken("avatar-empty@songly.io");

		mockMvc.perform(multipart("/api/v1/profiles/me/avatar")
				.file(new MockMultipartFile("file", "puste.png", MediaType.IMAGE_PNG_VALUE, new byte[0]))
				.header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMPTY_UPLOAD"));
	}

	/**
	 * Podmiana avatara kasuje poprzedni plik — inaczej każda zmiana zdjęcia
	 * zostawiałaby w magazynie kolejną kopię, której nic już nie wskazuje i po
	 * której nie ma śladu w bazie.
	 */
	@Test
	void replacingAvatar_removesThePreviousFile() throws Exception {
		String token = accessToken("avatar-replace@songly.io");
		String firstUrl = avatarUrlOf(uploadAvatar(token, pngBytes(), "stary.png", MediaType.IMAGE_PNG_VALUE, 200));

		String secondUrl = avatarUrlOf(uploadAvatar(token, jpegBytes(), "nowy.jpg", MediaType.IMAGE_JPEG_VALUE, 200));

		assertThat(secondUrl).isNotEqualTo(firstUrl);
		assertThat(fetchAnonymously(secondUrl).statusCode()).isEqualTo(200);
		assertThat(fetchAnonymously(firstUrl).statusCode()).isEqualTo(404);
	}

	@Test
	void deletingAvatar_clearsTheProfileAndTheFile() throws Exception {
		String token = accessToken("avatar-delete@songly.io");
		String url = avatarUrlOf(uploadAvatar(token, pngBytes(), "ja.png", MediaType.IMAGE_PNG_VALUE, 200));

		mockMvc.perform(delete("/api/v1/profiles/me/avatar").header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").doesNotExist());
		assertThat(fetchAnonymously(url).statusCode()).isEqualTo(404);
	}

	/**
	 * Avatara nie da się już ustawić dowolnym adresem przez edycję profilu. To był
	 * gotowy piksel śledzący: adres pod kontrolą obcej osoby renderowany w kliencie
	 * każdego, kto obejrzy ten profil (ADR 0010).
	 */
	@Test
	void profileUpdateCannotSetAnArbitraryAvatarUrl() throws Exception {
		String token = accessToken("avatar-arbitrary@songly.io");

		mockMvc.perform(put("/api/v1/profiles/me").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"displayName":"Ada","bio":"cos","avatarUrl":"https://tracker.example/pixel.gif"}"""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").doesNotExist());
	}

	/*
	 * Limitu rozmiaru pliku celowo NIE ma w tej klasie. Egzekwuje go kontener
	 * servletów przy parsowaniu multiparta — czyli zanim żądanie dotrze do
	 * kontrolera, i to jest właściwe miejsce, bo dzięki temu za duży plik nigdy nie
	 * zostaje w całości zbuforowany. MockMvc nie ma prawdziwego kontenera i
	 * przyjmuje MockMultipartFile gotowy, więc test tutaj sprawdzałby wyłącznie, że
	 * limit się NIE stosuje. Mapowanie MaxUploadSizeExceededException na 413
	 * FILE_TOO_LARGE — jedyną część, która może cicho zepsuć się w naszym kodzie —
	 * pilnuje GlobalExceptionHandlerTest.
	 */

	@Test
	void endpointRequiresAuthentication() throws Exception {
		mockMvc.perform(multipart("/api/v1/profiles/me/avatar")
				.file(new MockMultipartFile("file", "ja.png", MediaType.IMAGE_PNG_VALUE, pngBytes())))
				.andExpect(status().isUnauthorized());
	}
}
