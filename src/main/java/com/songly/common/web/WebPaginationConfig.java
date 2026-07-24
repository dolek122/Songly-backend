package com.songly.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

/**
 * Serializuje strony Spring Data przez stabilne DTO ({@code PagedModel})
 * zamiast surowego {@code PageImpl}. Bez tego Spring ostrzega, że kształt
 * JSON-a dla {@code Page<T>} nie jest gwarantowany i może się zmienić między
 * wersjami — a to kontrakt, z którego korzysta klient mobilny (KMP), więc musi
 * być stabilny.
 *
 * <p>
 * Efekt w JSON: metadane paginacji przenoszą się z pól najwyższego poziomu do
 * zagnieżdżonego obiektu {@code page} ({@code size}, {@code number},
 * {@code totalElements}, {@code totalPages}). Lista wyników pozostaje pod
 * {@code content}.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
class WebPaginationConfig {
}
