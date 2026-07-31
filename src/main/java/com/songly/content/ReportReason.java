package com.songly.content;

/**
 * Zamknięta lista powodów, a nie dowolny tekst: kolejkę trzeba dać się sortować
 * i filtrować, a przy wolnym polu każde zgłoszenie brzmi inaczej i moderator
 * czyta wszystko po kolei. Szczegóły idą do osobnego, opcjonalnego pola.
 */
public enum ReportReason {

	SPAM,

	HARASSMENT,

	HATE_SPEECH,

	ILLEGAL_CONTENT,

	OTHER
}
