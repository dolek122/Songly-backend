package com.songly.common.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Wysyłka poza wątkiem żądania (ADR 0041). Najważniejsze jest pierwsze
 * zachowanie: {@code send} wraca od razu, nawet gdy serwer pocztowy wisi —
 * inaczej czas odpowiedzi resetu hasła znów mówiłby, czy konto istnieje.
 */
class AsyncMailSenderTest {

	@Test
	void send_returnsImmediately_whileDelegateIsStillSending() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch delivered = new CountDownLatch(1);
		AsyncMailSender sender = new AsyncMailSender((to, subject, body) -> {
			await(release);
			delivered.countDown();
		}, 1, 10);

		sender.send("jan@songly.io", "temat", "treść");

		// Wróciliśmy, choć „serwer pocztowy" wciąż trzyma wiadomość.
		assertThat(delivered.getCount()).isEqualTo(1);
		release.countDown();
		assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
		sender.close();
	}

	@Test
	void failedDelivery_doesNotReachCaller() throws Exception {
		CountDownLatch attempted = new CountDownLatch(1);
		AsyncMailSender sender = new AsyncMailSender((to, subject, body) -> {
			attempted.countDown();
			throw new MailDeliveryException("SMTP leży", null);
		}, 1, 10);

		sender.send("jan@songly.io", "temat", "treść");

		assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
		sender.close();
	}

	/**
	 * Przepełniona kolejka to błąd, który wołający już łapią — nie blokada wątku
	 * żądania, bo ta przywróciłaby dokładnie to, przed czym ta klasa chroni.
	 */
	@Test
	void fullQueue_rejectsInsteadOfBlocking() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AsyncMailSender sender = new AsyncMailSender((to, subject, body) -> {
			started.countDown();
			await(release);
		}, 1, 1);

		sender.send("a@songly.io", "1", "zajmuje wątek");
		// Pierwsza wiadomość musi już zająć jedyny wątek, zanim druga trafi do kolejki
		// — inaczej test sprawdzałby wyścig, a nie pojemność.
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
		sender.send("b@songly.io", "2", "zajmuje kolejkę");

		assertThatThrownBy(() -> sender.send("c@songly.io", "3", "nie mieści się"))
				.isInstanceOf(MailDeliveryException.class);
		release.countDown();
		sender.close();
	}

	@Test
	void requestContext_travelsToSendingThread() throws Exception {
		List<String> seenRequestIds = new CopyOnWriteArrayList<>();
		CountDownLatch delivered = new CountDownLatch(1);
		AsyncMailSender sender = new AsyncMailSender((to, subject, body) -> {
			seenRequestIds.add(String.valueOf(MDC.get("requestId")));
			delivered.countDown();
		}, 1, 10);

		MDC.put("requestId", "req-123");
		try {
			sender.send("jan@songly.io", "temat", "treść");
		} finally {
			MDC.clear();
		}

		assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(seenRequestIds).containsExactly("req-123");
		sender.close();
	}

	@Test
	void close_waitsForAcceptedMessages() throws Exception {
		List<String> sent = new CopyOnWriteArrayList<>();
		AsyncMailSender sender = new AsyncMailSender((to, subject, body) -> {
			sleep(50);
			sent.add(to);
		}, 1, 10);

		sender.send("a@songly.io", "1", "x");
		sender.send("b@songly.io", "2", "x");
		sender.close();

		assertThat(sent).containsExactly("a@songly.io", "b@songly.io");
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
