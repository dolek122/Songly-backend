package com.songly.common.web;

import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
class WebPaginationConfig implements WebMvcConfigurer {

	private final ObjectProvider<PageableHandlerMethodArgumentResolver> springDataResolver;

	WebPaginationConfig(ObjectProvider<PageableHandlerMethodArgumentResolver> springDataResolver) {
		this.springDataResolver = springDataResolver;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new ServerOrderedPageableResolver(springDataResolver.getObject()));
	}

	@Bean
	GlobalOpenApiCustomizer pageableWithoutSort() {
		return openApi -> {
			if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
				return;
			}
			Schema<?> pageable = openApi.getComponents().getSchemas().get("Pageable");
			if (pageable != null && pageable.getProperties() != null) {
				pageable.getProperties().remove("sort");
			}
		};
	}

	static final class ServerOrderedPageableResolver implements HandlerMethodArgumentResolver {

		private final PageableHandlerMethodArgumentResolver delegate;

		ServerOrderedPageableResolver(PageableHandlerMethodArgumentResolver delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean supportsParameter(MethodParameter parameter) {
			return Pageable.class.equals(parameter.getParameterType());
		}

		@Override
		public Pageable resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
				NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
			Pageable pageable = delegate.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
			return pageable.isPaged() ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()) : pageable;
		}
	}
}
