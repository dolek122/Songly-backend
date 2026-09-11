package com.songly.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Publiczna granica modułu identity dla modułów, które wysyłają wiadomości
 * e-mail do użytkowników (Claude.md §2). Do powstania ADR 0013 adres znał
 * wyłącznie sam identity — i wszystkie maile (weryfikacja, reset hasła)
 * wychodziły z niego.
 *
 * <p>
 * <b>Adres e-mail to dane wrażliwe.</b> Konsument dostaje go wyłącznie po to,
 * żeby przekazać {@code MailSender.send} — nie wolno go logować (Claude.md §13:
 * w logach identyfikujemy po {@code userId}) ani zapisywać po swojej stronie.
 */
public interface UserEmailDirectory {

	/**
	 * Adresy e-mail podanych użytkowników. Brakujące userId (konto w międzyczasie
	 * usunięte) po prostu nie występują w wyniku — ta sama konwencja co w
	 * {@code ProfileDirectory}.
	 */
	Map<UUID, String> emailsOf(Collection<UUID> userIds);
}
