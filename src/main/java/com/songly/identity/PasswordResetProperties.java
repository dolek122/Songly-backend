package com.songly.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.password-reset")
class PasswordResetProperties {

	private Duration ttl = Duration.ofMinutes(30);

	private Duration resendInterval = Duration.ofMinutes(1);

	private String resetUrl = "songly://reset-password?token={token}";

	/**
	 * Znacznie krócej niż refresh token (30 dni), bo to zupełnie inny byt: link
	 * leży w skrzynce pocztowej, czyli w systemie, nad którym nie mamy żadnej
	 * kontroli. Pół godziny wystarcza na przeczytanie maila i nie zostawia w cudzej
	 * skrzynce klucza do konta na tygodnie.
	 */
	Duration getTtl() {
		return ttl;
	}

	void setTtl(Duration ttl) {
		this.ttl = ttl;
	}

	/**
	 * Odstęp między kolejnymi wysyłkami dla <b>tego samego konta</b>. Rate limiting
	 * jest per IP (Claude.md §9), więc bez tego endpoint byłby darmowym generatorem
	 * poczty na cudzy adres: wystarczyłoby rozłożyć żądania na kilka adresów IP,
	 * żeby zasypać ofiarę mailami, których nigdy nie zamawiała.
	 */
	Duration getResendInterval() {
		return resendInterval;
	}

	void setResendInterval(Duration resendInterval) {
		this.resendInterval = resendInterval;
	}

	/**
	 * Szablon linku z placeholderem {@code {token}}. Wskazuje na <b>aplikację
	 * kliencką</b>, nie na backend: to klient pokazuje formularz nowego hasła i
	 * dopiero on woła API. Backend nie ma żadnego UI, więc wysłanie użytkownika
	 * wprost na endpoint REST pokazałoby mu surowy JSON.
	 */
	String getResetUrl() {
		return resetUrl;
	}

	void setResetUrl(String resetUrl) {
		this.resetUrl = resetUrl;
	}
}
