package com.songly.common.mail;

/**
 * Wysyłka wiadomości e-mail. Celowo bez wiedzy o tym, <b>co</b> wysyłamy —
 * treść składa moduł domenowy, ten interfejs zna wyłącznie adresata, temat i
 * tekst (Claude.md §3, reguła 3: {@code common} nie zna domeny).
 *
 * <p>
 * Implementacja może rzucić {@link MailDeliveryException}. Wołający musi się
 * liczyć z tym, że wysyłka zawodzi z powodów całkowicie poza aplikacją — i sam
 * zdecydować, czy to psuje jego operację, czy tylko zasługuje na wpis w logu.
 */
public interface MailSender {

	void send(String to, String subject, String body);
}
