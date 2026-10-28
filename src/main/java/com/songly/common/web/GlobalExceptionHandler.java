package com.songly.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
		return ResponseEntity.status(ex.getStatus()).body(toApiError(ex.getCode(), ex.getMessage(), request));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.reduce((a, b) -> a + "; " + b).orElse("Nieprawidłowe dane wejściowe");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(toApiError("VALIDATION_ERROR", message, request));
	}

	/**
	 * Celowo bez {@code ex.getMessage()} w odpowiedzi — komunikaty Jacksona
	 * potrafią zawierać nazwy klas i fragmenty payloadu, czyli szczegóły
	 * implementacyjne wystawione publicznemu klientowi.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
				toApiError("MALFORMED_REQUEST", "Treść żądania jest pusta lub nie jest poprawnym JSON-em", request));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex,
			HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(toApiError("MISSING_PARAMETER", "Brak wymaganego parametru: " + ex.getParameterName(), request));
	}

	/**
	 * Plik większy niż {@code spring.servlet.multipart.max-file-size}. Bez tego
	 * handlera wpada do łapacza {@code Exception} niżej i wraca jako 500 ze stack
	 * tracem — czyli za duży avatar wygląda w monitoringu jak awaria serwera, a
	 * klient nie ma jak odróżnić „zmniejsz plik" od „spróbuj później".
	 *
	 * <p>
	 * Limit egzekwuje kontener servletów, zanim bajty w ogóle dotrą do kontrolera —
	 * i o to chodzi: sprawdzanie rozmiaru dopiero w kodzie wymagałoby najpierw
	 * przyjęcia całego pliku.
	 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
				.body(toApiError("FILE_TOO_LARGE", "Przesłany plik jest za duży", request));
	}

	/**
	 * Ciało w formacie, którego endpoint nie przyjmuje (np. {@code text/plain}
	 * zamiast JSON-a). Wcześniej wpadało do łapacza {@code Exception} jako 500 ze
	 * stack tracem, a boty potrafią to wywołać dowolnie często.
	 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
			HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
				.body(toApiError("UNSUPPORTED_MEDIA_TYPE", "Nieobsługiwany format treści żądania", request));
	}

	/** Np. upload awatara bez części {@code file}. */
	@ExceptionHandler(MissingServletRequestPartException.class)
	ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
				toApiError("MISSING_PARAMETER", "Brak wymaganej części żądania: " + ex.getRequestPartName(), request));
	}

	/**
	 * Brak wymaganego nagłówka, ciasteczka albo atrybutu żądania. Nazwy nie
	 * podajemy, bo bywa nią szczegół implementacji, a klientowi wystarczy wiedzieć,
	 * że żądanie jest niekompletne.
	 */
	@ExceptionHandler(ServletRequestBindingException.class)
	ResponseEntity<ApiError> handleRequestBinding(ServletRequestBindingException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(toApiError("MALFORMED_REQUEST", "Żądanie jest niekompletne", request));
	}

	/** Ograniczenia na parametrach metody kontrolera (nie na ciele JSON). */
	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(toApiError("VALIDATION_ERROR", "Nieprawidłowe dane wejściowe", request));
	}

	/** Np. nie-UUID w ścieżce {@code /posts/{postId}}. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(toApiError("INVALID_PARAMETER", "Nieprawidłowa wartość parametru: " + ex.getName(), request));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
			HttpServletRequest request) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
		if (ex.getSupportedHttpMethods() != null) {
			response.allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]));
		}
		return response.body(toApiError("METHOD_NOT_ALLOWED",
				"Metoda " + ex.getMethod() + " nie jest obsługiwana dla tego zasobu", request));
	}

	/**
	 * Nieznana ścieżka (np. literówka w URL-u klienta). Bez tego handlera
	 * {@link NoResourceFoundException} wpada do łapacza {@code Exception} niżej i
	 * daje 500 {@code INTERNAL_ERROR} ze stack tracem — czyli zły endpoint wygląda
	 * jak awaria serwera i zasypuje monitoring fałszywymi 500-tkami.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(toApiError("NOT_FOUND", "Nie znaleziono zasobu: " + request.getRequestURI(), request));
	}

	/**
	 * Zapis odrzucony przez ograniczenie w bazie: klucz główny, unikalność albo
	 * klucz obcy. Najczęściej to wyścig — podwójne stuknięcie w „lubię" wysyła dwa
	 * żądania, oba sprawdzają „jeszcze nie polubione", a drugi INSERT trafia w
	 * klucz główny. Bywa też odwołaniem do czegoś, co właśnie zniknęło (ban na
	 * konto usunięte chwilę wcześniej).
	 *
	 * <p>
	 * 409, nie 500: żądanie koliduje ze stanem danych, a serwer działa. Bez tego
	 * każdy taki wyścig wyglądał w monitoringu jak awaria i dawał klientowi
	 * „spróbuj później" zamiast „odśwież". Stack trace zostaje w logu, bo ten sam
	 * objaw daje brakujący listener kasowania konta (Claude.md §9) — to ma być
	 * widać. Komunikat bez szczegółów: nazwa ograniczenia mówi o schemacie bazy.
	 *
	 * <p>
	 * Ten sam wyścig od drugiej strony to
	 * {@link OptimisticLockingFailureException}: podwójne „cofnij lajk” wczytuje
	 * wiersz dwa razy, a drugi DELETE trafia w wiersz już usunięty przez pierwszy
	 * ({@code expected row count 1 but was 0}). To też konflikt ze stanem danych,
	 * nie awaria.
	 */
	@ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
	ResponseEntity<ApiError> handleDataIntegrityViolation(DataAccessException ex, HttpServletRequest request) {
		log.warn("Zapis odrzucony przez ograniczenie w bazie: {} {}", request.getMethod(), request.getRequestURI(), ex);
		return ResponseEntity.status(HttpStatus.CONFLICT).body(toApiError("CONFLICT",
				"Żądanie koliduje z bieżącym stanem danych — odśwież i spróbuj ponownie", request));
	}

	/**
	 * Jedyne miejsce, które ma prawo zwrócić 500 — dlatego jako jedyne loguje stack
	 * trace. Bez tego wyjątek obsłużony przez {@code @ExceptionHandler} znika bez
	 * śladu (Spring nie loguje go sam), a 500-tka bez logu jest niediagnozowalna.
	 * Logujemy metodę i ścieżkę, nigdy ciało żądania — mogłoby zawierać hasło albo
	 * token.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Nieobsłużony wyjątek: {} {}", request.getMethod(), request.getRequestURI(), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(toApiError("INTERNAL_ERROR", "Nieoczekiwany błąd serwera", request));
	}

	private ApiError toApiError(String code, String message, HttpServletRequest request) {
		return new ApiError(code, message, Instant.now(), request.getRequestURI());
	}
}
