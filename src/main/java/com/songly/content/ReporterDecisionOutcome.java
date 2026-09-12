package com.songly.content;

/**
 * Jak skończyło się zgłoszenie (ADR 0030). Osobny enum, a nie
 * {@link ReportStatus}: tam istnieje jeszcze {@code PENDING}, a wiersz decyzji
 * powstaje wyłącznie wtedy, gdy decyzja już zapadła — typ, który dopuszcza
 * „nierozstrzygnięte", zapraszałby do zapisania takiego wiersza.
 */
enum ReporterDecisionOutcome {

	/** Post usunięty przez moderację — zgłoszenie było trafne. */
	UPHELD,

	/** Moderator uznał, że post zostaje. */
	DISMISSED
}
