package com.songly.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.account-deletion")
class AccountDeletionProperties {

	private Duration ttl = Duration.ofHours(1);

	private Duration resendInterval = Duration.ofMinutes(1);

	private String confirmUrl = "http://localhost:8080/account-deletion/confirm?token={token}";

	/**
	 * Godzina, nie pół jak przy resecie: link prowadzi do nieodwracalnej decyzji,
	 * więc ktoś może chcieć przeczytać stronę do końca, zanim kliknie. Dłużej nie
	 * ma po co — kto się rozmyśli i wróci za tydzień, zamówi nowy link.
	 */
	Duration getTtl() {
		return ttl;
	}

	void setTtl(Duration ttl) {
		this.ttl = ttl;
	}

	/**
	 * Odstęp między wysyłkami na to samo konto — ta sama ochrona cudzej skrzynki co
	 * przy resecie hasła, bo strona jest publiczna, a rate limiting liczy per IP.
	 */
	Duration getResendInterval() {
		return resendInterval;
	}

	void setResendInterval(Duration resendInterval) {
		this.resendInterval = resendInterval;
	}

	/**
	 * Szablon linku z {@code {token}}. W przeciwieństwie do linków z ADR 0037 to
	 * adres <b>HTTP backendu</b>, nie {@code songly://}: cała ta ścieżka istnieje
	 * dla kogoś, kto aplikacji już nie ma.
	 *
	 * <p>
	 * Z konfiguracji, a nie z nagłówka {@code Host} żądania. Adres złożony z
	 * żądania dałoby się podmienić podrobionym nagłówkiem, a wtedy link w mailu do
	 * ofiary prowadziłby na serwer napastnika razem z tokenem, który kasuje jej
	 * konto.
	 */
	String getConfirmUrl() {
		return confirmUrl;
	}

	void setConfirmUrl(String confirmUrl) {
		this.confirmUrl = confirmUrl;
	}
}
