package com.songly.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Mapowania, których nie da się wywołać przez MockMvc, bo powstają poza naszym
 * kodem — w kontenerze servletów.
 *
 * <p>
 * Test jednostkowy, a nie integracyjny, i to jest tu jedyna uczciwa opcja:
 * limit rozmiaru pliku egzekwuje kontener przy parsowaniu multiparta, a MockMvc
 * kontenera nie ma. Sprawdzamy więc dokładnie tę część, która należy do nas —
 * że wyjątek nie wpadnie do łapacza {@code Exception} i nie wróci jako 500.
 */
class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	/**
	 * Bez tego mapowania za duży plik daje 500 {@code INTERNAL_ERROR} ze stack
	 * tracem: w monitoringu wygląda jak awaria serwera, a klient nie ma jak
	 * odróżnić „zmniejsz plik" od „spróbuj później".
	 */
	@Test
	void maxUploadSizeExceeded_mapsToPayloadTooLarge() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/profiles/me/avatar");

		ResponseEntity<ApiError> response = handler.handleUploadTooLarge(new MaxUploadSizeExceededException(5_242_880L),
				request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo("FILE_TOO_LARGE");
		assertThat(response.getBody().path()).isEqualTo("/api/v1/profiles/me/avatar");
	}
}
