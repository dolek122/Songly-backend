package com.songly.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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

	@Test
	void dataIntegrityViolation_mapsToConflictWithoutSchemaDetails() {
		MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/posts/1/likes");

		ResponseEntity<ApiError> response = handler.handleDataIntegrityViolation(
				new DataIntegrityViolationException("duplicate key value violates unique constraint \"likes_pkey\""),
				request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo("CONFLICT");
		assertThat(response.getBody().message()).doesNotContain("likes_pkey");
	}

	@Test
	void optimisticLockingFailure_mapsToConflict() {
		ObjectOptimisticLockingFailureException ex = new ObjectOptimisticLockingFailureException(Object.class, "1");
		MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/posts/1/likes");

		Method resolved = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class).resolveMethod(ex);
		ResponseEntity<ApiError> response = handler.handleDataIntegrityViolation(ex, request);

		assertThat(resolved).isNotNull();
		assertThat(resolved.getName()).isEqualTo("handleDataIntegrityViolation");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo("CONFLICT");
	}
}
