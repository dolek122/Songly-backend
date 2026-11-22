package com.songly.common.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

class MailConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
			.withUserConfiguration(MailConfig.class);

	@Test
	void smtpWithHost_startsAndWrapsBootSender() {
		contextRunner.withPropertyValues("songly.mail.provider=smtp", "spring.mail.host=smtp.example.test")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(JavaMailSender.class);
					assertThat(context).getBean(MailSender.class).isInstanceOf(AsyncMailSender.class);
				});
	}

	@Test
	void smtpWithoutHost_failsStartup() {
		contextRunner.withPropertyValues("songly.mail.provider=smtp").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("spring.mail.host");
		});
	}

	@Test
	void defaultProvider_logsInsteadOfSending() {
		contextRunner.withPropertyValues("spring.mail.host=smtp.example.test").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).getBean(MailSender.class).isInstanceOf(LoggingMailSender.class);
		});
	}
}
