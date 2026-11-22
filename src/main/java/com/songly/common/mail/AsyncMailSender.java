package com.songly.common.mail;

import com.songly.common.concurrent.BoundedExecutor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AsyncMailSender implements MailSender, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(AsyncMailSender.class);

	private final MailSender delegate;
	private final BoundedExecutor executor;

	AsyncMailSender(MailSender delegate, int threads, int queueCapacity) {
		this.delegate = delegate;
		this.executor = new BoundedExecutor("mail-sender", threads, queueCapacity);
	}

	@Override
	public void send(String to, String subject, String body) {
		try {
			executor.execute(() -> deliver(to, subject, body));
		} catch (RejectedExecutionException ex) {
			throw new MailDeliveryException("Kolejka wysyłki e-maili jest pełna", ex);
		}
	}

	private void deliver(String to, String subject, String body) {
		try {
			delegate.send(to, subject, body);
		} catch (RuntimeException ex) {
			log.error("Nie udało się wysłać wiadomości e-mail", ex);
		}
	}

	@Override
	public void close() throws InterruptedException {
		executor.close();
	}
}
