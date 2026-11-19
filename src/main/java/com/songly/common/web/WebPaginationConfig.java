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

/**
 * Strony Spring Data serializują się przez stabilne DTO ({@code PagedModel})
 * zamiast surowego {@code PageImpl}: kształt JSON-a dla {@code Page<T>} nie
 * jest inaczej gwarantowany między wersjami, a to kontrakt, z którego korzysta
 * klient mobilny (KMP). Ustawia to
 * {@code spring.data.web.pageable.serialization-mode=via-dto}, <b>nie</b>
 * {@code @EnableSpringDataWebSupport}: adnotacja wyłączała autokonfigurację
 * Boota, a razem z nią wszystkie {@code spring.data.web.pageable.*}, więc sufit
 * {@code max-page-size=100} z §6 nigdy nie działał i każda lista oddawała do
 * 2000 wierszy na żądanie (ADR 0051).
 *
 * <p>
 * Efekt w JSON: metadane paginacji przenoszą się z pól najwyższego poziomu do
 * zagnieżdżonego obiektu {@code page} ({@code size}, {@code number},
 * {@code totalElements}, {@code totalPages}). Lista wyników pozostaje pod
 * {@code content}.
 *
 * <p>
 * <b>Kolejność należy do serwera, parametr {@code sort} jest ignorowany</b>
 * (ADR 0051). Kontrakt to {@code ?page=&size=} (§6), a każda lista ma kolejność
 * zapisaną w zapytaniu: feed od najnowszych, kolejka zgłoszeń od najstarszych.
 * Spring Data dokładał {@code sort} od klienta do każdego zapytania, więc
 * {@code ?sort=foo} kończył się 500 na każdej liście, a {@code ?sort=bio}
 * sortował po dowolnym polu encji, także niezaindeksowanym i niewystawionym w
 * odpowiedzi. Odrzucanie przez 400 wymagałoby rozpoznania trzech różnych
 * wyjątków (zapytania pochodne, JPQL, natywne), z których ostatni wygląda tak
 * samo jak prawdziwy błąd w SQL-u. Zapytanie stronicowane <b>musi więc mieć
 * własne {@code ORDER BY}</b>: bez niego kolejne strony mogą się nakładać, a
 * {@code @PageableDefault(sort = ...)} nic tu nie zmieni.
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
class WebPaginationConfig implements WebMvcConfigurer {

	private final ObjectProvider<PageableHandlerMethodArgumentResolver> springDataResolver;

	WebPaginationConfig(ObjectProvider<PageableHandlerMethodArgumentResolver> springDataResolver) {
		this.springDataResolver = springDataResolver;
	}

	/**
	 * Przed resolverem Spring Data, bo wygrywa pierwszy, który obsługuje typ
	 * parametru — stąd {@code HIGHEST_PRECEDENCE} na klasie.
	 */
	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new ServerOrderedPageableResolver(springDataResolver.getObject()));
	}

	/**
	 * Kontrakt nie może obiecywać parametru, którego serwer nie czyta. Springdoc
	 * opisuje {@code Pageable} z {@code sort}, więc go stamtąd usuwamy.
	 */
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

	/**
	 * Strona i rozmiar idą przez resolver Spring Data, więc zostają
	 * {@code @PageableDefault} i sufit {@code max-page-size}. Odcinamy wyłącznie
	 * sortowanie.
	 */
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
