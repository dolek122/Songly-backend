package com.songly.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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
