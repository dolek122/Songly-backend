package com.songly.matching;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.matching")
class MatchingProperties {

	private double confidencePrior = 2.0;

	/**
	 * Ile „nieznanego gustu" dokładamy obu stronom, zanim uwierzymy w wynik (ADR
	 * 0033). Wyrażone w tych samych jednostkach co wagi gatunków, czyli
	 * {@code ln(1 + N/n)} — domyślne 2.0 to z grubsza jeden typowy gatunek.
	 *
	 * <p>
	 * Stała siedzi w mianowniku wyniku, więc działa sama z siebie proporcjonalnie:
	 * przy ubogim profilu dominuje (dwie osoby z jednym wspólnym gatunkiem nie
	 * wyglądają na bliźniaków), przy bogatym znika w tle. Podniesienie jej znaczy
	 * „bądź bardziej nieufny wobec krótkich profili", obniżenie — odwrotnie; zero
	 * przywraca zachowanie sprzed tego ADR-u.
	 */
	double getConfidencePrior() {
		return confidencePrior;
	}

	void setConfidencePrior(double confidencePrior) {
		this.confidencePrior = confidencePrior;
	}
}
