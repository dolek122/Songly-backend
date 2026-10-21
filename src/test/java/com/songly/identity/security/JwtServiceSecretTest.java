package com.songly.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Odmowa startu z sekretem deweloperskim (Claude.md §9) sprawdzana na
 * prawdziwym kontekście Springa — tylko tak widać, że flaga dociera przez
 * {@code @Value} i że sprawdzenie odpala się przy starcie, a nie dopiero przy
 * pierwszym tokenie.
 */
class JwtServiceSecretTest {

	private static final String DEV_SECRET = "dev-only-secret-change-me-in-every-real-environment-32bytes+";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(JwtService.class);

	@Test
	void contextFails_withDevSecret_whereProductionSecretsAreRequired() {
		runner.withPropertyValues("songly.security.jwt.secret=" + DEV_SECRET,
				"songly.security.production-secrets-required=true").run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("JWT_SECRET");
				});
	}

	@Test
	void contextStarts_withDevSecret_locally() {
		runner.withPropertyValues("songly.security.jwt.secret=" + DEV_SECRET)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void contextStarts_withRealSecret_whereProductionSecretsAreRequired() {
		runner.withPropertyValues("songly.security.jwt.secret=Zq8v3Nf0a7LwXy2Pk5Rt9Bc4Hd6Jm1Qs8Vu0Ge3Ti7Ko",
				"songly.security.production-secrets-required=true").run(context -> assertThat(context).hasNotFailed());
	}
}
