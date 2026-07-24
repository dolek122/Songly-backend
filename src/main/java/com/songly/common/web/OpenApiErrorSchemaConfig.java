package com.songly.common.web;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rejestruje schemat {@link ApiError} w kontrakcie OpenAPI. Format błędu z
 * {@link GlobalExceptionHandler} jest zwracany z każdej ścieżki błędu
 * (walidacja, 401/403/404/429/500), ale springdoc sam go nie widzi —
 * {@code @ExceptionHandler} nie jest typem zwracanym żadnej operacji. Bez tego
 * klient (mobilka) nie ma czym zweryfikować swojego modelu błędu wobec
 * kontraktu.
 */
@Configuration
class OpenApiErrorSchemaConfig {

	@Bean
	GlobalOpenApiCustomizer errorSchemaCustomizer() {
		return openApi -> {
			if (openApi.getComponents() == null) {
				openApi.setComponents(new Components());
			}
			ModelConverters.getInstance().read(ApiError.class).forEach(openApi.getComponents()::addSchemas);
		};
	}
}
