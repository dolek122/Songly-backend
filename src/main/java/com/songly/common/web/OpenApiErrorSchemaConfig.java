package com.songly.common.web;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
