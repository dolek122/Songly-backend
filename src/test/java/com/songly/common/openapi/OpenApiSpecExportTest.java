package com.songly.common.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.songly.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Utrzymuje żywy kontrakt OpenAPI w {@code docs/api/openapi.json}, żeby zespół
 * mobilny (KMP, Claude.md §14) mógł generować z niego klienta/modele bez
 * odpalania backendu.
 *
 * <p>
 * Dwa tryby:
 * <ul>
 * <li><b>Weryfikacja (domyślny, tak działa CI):</b> generuje spec ze świeżo
 * zbootowanego kontekstu i porównuje z zacommitowanym plikiem. Rozjazd = błąd —
 * ktoś zmienił API i nie odświeżył kontraktu, więc mobilka pracowałaby na
 * nieaktualnym pliku.</li>
 * <li><b>Regeneracja:</b> uruchom z {@code -Dopenapi.export=true}, żeby
 * nadpisać plik świeżym specem, a potem zacommituj wynik.</li>
 * </ul>
 *
 * <p>
 * Spec bierze się z realnie zarejestrowanych kontrolerów (springdoc na pełnym
 * kontekście, te same Testcontainers co reszta testów integracyjnych), a nie z
 * ręcznie utrzymywanej kopii.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class OpenApiSpecExportTest extends AbstractIntegrationTest {

	private static final Path SPEC_PATH = Path.of("docs", "api", "openapi.json");

	private static final String REGENERATE_HINT = "Kontrakt OpenAPI (" + SPEC_PATH
			+ ") jest nieaktualny względem kodu. Odśwież go i zacommituj:\n"
			+ "    ./mvnw test -Dtest=OpenApiSpecExportTest -Dopenapi.export=true";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void committedSpecMatchesLiveApi() throws Exception {
		String rawSpec = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		// Pretty-print z posortowanymi kluczami, żeby diff między odświeżeniami
		// był stabilny (springdoc nie gwarantuje kolejności pól w JSON-ie).
		ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
				.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		Object tree = mapper.readValue(rawSpec, Object.class);
		String expected = mapper.writeValueAsString(tree) + "\n";

		if (Boolean.getBoolean("openapi.export")) {
			Files.createDirectories(SPEC_PATH.getParent());
			Files.writeString(SPEC_PATH, expected, StandardCharsets.UTF_8);
			return;
		}

		assertThat(SPEC_PATH).as(REGENERATE_HINT).exists();
		String committed = Files.readString(SPEC_PATH, StandardCharsets.UTF_8);
		assertThat(committed).as(REGENERATE_HINT).isEqualTo(expected);
	}
}
