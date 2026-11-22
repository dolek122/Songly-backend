package com.songly.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
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
		ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
		if (ex.getRetryAfterSeconds() != null) {
			response.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
		}
		return response.body(toApiError(ex.getCode(), ex.getMessage(), request));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.reduce((a, b) -> a + "; " + b).orElse("Nieprawidłowe dane wejściowe");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(toApiError("VALIDATION_ERROR", message, request));
	}

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

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
				.body(toApiError("FILE_TOO_LARGE", "Przesłany plik jest za duży", request));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
			HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
				.body(toApiError("UNSUPPORTED_MEDIA_TYPE", "Nieobsługiwany format treści żądania", request));
	}

	@ExceptionHandler(MissingServletRequestPartException.class)
	ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
				toApiError("MISSING_PARAMETER", "Brak wymaganej części żądania: " + ex.getRequestPartName(), request));
	}

	@ExceptionHandler(ServletRequestBindingException.class)
	ResponseEntity<ApiError> handleRequestBinding(ServletRequestBindingException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(toApiError("MALFORMED_REQUEST", "Żądanie jest niekompletne", request));
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(toApiError("VALIDATION_ERROR", "Nieprawidłowe dane wejściowe", request));
	}

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

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(toApiError("NOT_FOUND", "Nie znaleziono zasobu: " + request.getRequestURI(), request));
	}

	@ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
	ResponseEntity<ApiError> handleDataIntegrityViolation(DataAccessException ex, HttpServletRequest request) {
		log.warn("Zapis odrzucony przez ograniczenie w bazie: {} {}", request.getMethod(), request.getRequestURI(), ex);
		return ResponseEntity.status(HttpStatus.CONFLICT).body(toApiError("CONFLICT",
				"Żądanie koliduje z bieżącym stanem danych — odśwież i spróbuj ponownie", request));
	}

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
