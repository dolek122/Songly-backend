package com.songly.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.registration")
class RegistrationProperties {

	private Duration ttl = Duration.ofHours(24);

	private Duration resendInterval = Duration.ofMinutes(1);

	private String verificationUrl = "songly://verify-email?token={token}";

	/**
	 * Dłużej niż token resetu hasła (30 min) i celowo. Reset robi się w reakcji na
	 * problem, więc użytkownik siedzi wtedy przy skrzynce. Rejestrację często
	 * zaczyna się „na później" — wygaśnięcie po pół godzinie zmuszałoby połowę
	 * ludzi do zaczynania od zera. Doba to kompromis: link nie leży w cudzej
	 * skrzynce tygodniami, a i tak nie otwiera niczego, co już istnieje.
	 */
	Duration getTtl() {
		return ttl;
	}

	void setTtl(Duration ttl) {
		this.ttl = ttl;
	}

	/**
	 * Odstęp między wysyłkami na ten sam adres — ta sama ochrona co przy resecie
	 * hasła. Rate limiting liczy per IP, więc sam nie powstrzymuje zasypywania
	 * cudzej skrzynki z wielu adresów.
	 */
	Duration getResendInterval() {
		return resendInterval;
	}

	void setResendInterval(Duration resendInterval) {
		this.resendInterval = resendInterval;
	}

	/**
	 * Szablon linku z placeholderem {@code {token}}, prowadzący do aplikacji
	 * klienckiej — backend nie ma UI, więc wysłanie użytkownika wprost na endpoint
	 * REST pokazałoby mu surowy JSON.
	 */
	String getVerificationUrl() {
		return verificationUrl;
	}

	void setVerificationUrl(String verificationUrl) {
		this.verificationUrl = verificationUrl;
	}
}
