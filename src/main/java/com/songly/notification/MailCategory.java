package com.songly.notification;

/**
 * Kategoria maila w rozumieniu preferencji (ADR 0020). Celowo <b>dwie</b>, a
 * nie przełącznik na każdy {@link NotificationType}: piętnaście typów × kanały
 * to ustawienia, których nikt nie przejrzy, a każdy nowy typ wymagałby decyzji
 * o domyślnej wartości. Te dwie odpowiadają dwóm różnym rolom, w jakich
 * człowiek dostaje maila.
 */
enum MailCategory {

	/**
	 * Ban, usunięcie mojego posta, rozstrzygnięcie mojego zgłoszenia lub odwołania.
	 */
	DECISION_ABOUT_ME,

	/** Wezwanie do pracy moderacyjnej: nowe zgłoszenie, nowe odwołanie. */
	MODERATION_QUEUE
}
