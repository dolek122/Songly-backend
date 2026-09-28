package com.songly.common.web;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reguły limitu per użytkownik (ADR 0034). Reguła to <b>nazwana grupa</b>
 * ścieżek i metod z jednym wspólnym licznikiem — nie limit per ścieżka. Liczy
 * się to, ile jedna osoba robi danej <i>rodzaju</i> rzeczy, a nie którym
 * endpointem: lajk, komentarz i zaproszenie do znajomych kończą się tym samym
 * powiadomieniem u kogoś innego, więc rozłożenie zasypywania na trzy ścieżki
 * nie może dawać trzech budżetów.
 *
 * <p>
 * Żądanie liczy się do <b>każdej</b> reguły, do której pasuje, i jest
 * odrzucane, gdy przekroczy którąkolwiek. Nadpisanie {@code rules} w
 * konfiguracji zastępuje całą listę, nie dopisuje do niej.
 */
@ConfigurationProperties(prefix = "songly.security.user-rate-limit")
class UserRateLimitProperties {

	/** Metody zapisu — odczyty celowo nie są limitowane (ADR 0034). */
	private static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

	private boolean enabled = true;

	private List<Rule> rules = List.of(
			// Budżet na wszystkie zapisy. Cały prefiks, nie lista endpointów: nowy
			// endpoint jest wtedy objęty domyślnie, a nie dopiero gdy ktoś dopisze go
			// tutaj (ta sama zasada co /api/v1/admin/** w SecurityConfig). Poziom jest
			// nieosiągalny ręcznie — ma odciąć skrypt, nie człowieka.
			new Rule("writes", WRITE_METHODS, List.of("/api/**"), 60, Duration.ofMinutes(1)),
			// Ścieżki, które kończą się powiadomieniem u KOGOŚ INNEGO. Tylko kierunek
			// „zaczepiający" (PUT lajka, nie DELETE): cofnięcie własnego śladu nie może
			// zależeć od limitu, tak jak nie zależy od blokady (§9). Dłuższe okno, bo
			// szkodą jest wolumen u jednej ofiary, a nie chwilowe natężenie.
			new Rule("interactions", List.of("PUT", "POST"), List.of("/api/v1/posts/*/likes", "/api/v1/posts/*/shares",
					"/api/v1/posts/*/comments", "/api/v1/friends/requests"), 40, Duration.ofMinutes(10)));

	boolean isEnabled() {
		return enabled;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	List<Rule> getRules() {
		return rules;
	}

	void setRules(List<Rule> rules) {
		this.rules = rules;
	}

	static class Rule {

		/**
		 * Trafia do klucza w Redisie i do tagu metryki — musi więc być stałą z
		 * konfiguracji, nigdy niczym pochodzącym z żądania (kardynalność, §13).
		 */
		private String name;
		private List<String> methods = WRITE_METHODS;
		private List<String> paths = List.of();
		private int limit;
		private Duration window = Duration.ofMinutes(1);

		Rule() {
		}

		Rule(String name, List<String> methods, List<String> paths, int limit, Duration window) {
			this.name = name;
			this.methods = methods;
			this.paths = paths;
			this.limit = limit;
			this.window = window;
		}

		String getName() {
			return name;
		}

		void setName(String name) {
			this.name = name;
		}

		List<String> getMethods() {
			return methods;
		}

		void setMethods(List<String> methods) {
			this.methods = methods;
		}

		List<String> getPaths() {
			return paths;
		}

		void setPaths(List<String> paths) {
			this.paths = paths;
		}

		int getLimit() {
			return limit;
		}

		void setLimit(int limit) {
			this.limit = limit;
		}

		Duration getWindow() {
			return window;
		}

		void setWindow(Duration window) {
			this.window = window;
		}
	}
}
