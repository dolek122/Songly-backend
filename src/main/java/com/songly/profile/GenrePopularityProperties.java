package com.songly.profile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.profile.genre-popularity")
class GenrePopularityProperties {

	private Duration ttl = Duration.ofHours(1);

	/**
	 * Jak długo żyje zapamiętany obraz popularności gatunków (ADR 0032).
	 *
	 * <p>
	 * Godzina jest długa jak na cache, i to jest sedno: popularność gatunku zmienia
	 * się wolno, bo zmienia ją wyłącznie ktoś, kto edytuje swoje ulubione gatunki.
	 * TTL nie jest tu więc mechanizmem świeżości — świeżość zapewnia unieważnianie
	 * przy każdej zmianie — tylko <b>siatką bezpieczeństwa</b> na zmiany, które
	 * obeszły kod aplikacji: migrację, ręczny {@code UPDATE}, przywrócenie backupu.
	 * Dlatego może być tak długi.
	 */
	Duration getTtl() {
		return ttl;
	}

	void setTtl(Duration ttl) {
		this.ttl = ttl;
	}
}
