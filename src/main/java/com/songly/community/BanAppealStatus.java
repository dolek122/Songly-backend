package com.songly.community;

/**
 * Stan odwołania od banu (ADR 0017), rozszerzony o drugą instancję (ADR 0028).
 * Brak odwołania to {@code null} na encji/DTO, nie osobna wartość — stan „można
 * się odwołać" wynika z samego istnienia aktywnego banu bez odwołania.
 *
 * <p>
 * Stanu „uwzględnione" nie ma na żadnym z dwóch szczebli: uznanie odwołania to
 * zdjęcie banu, więc wiersz znika razem ze sprawą, a odwołujący dowiaduje się z
 * powiadomienia.
 *
 * <p>
 * Publiczny jak {@link CommunityRole}, bo występuje w DTO — ale żaden inny
 * moduł nie ma prawa go dotykać (lekcja z ADR 0016: zdarzenia niosą fakty, nie
 * słownik tego modułu).
 */
public enum BanAppealStatus {

	/** Czeka na moderację społeczności. */
	PENDING,

	/**
	 * Moderacja społeczności odmówiła. <b>Nie jest to już koniec drogi</b> (ADR
	 * 0028): z tego stanu — i tylko z tego — zbanowany może przekazać sprawę
	 * administracji platformy.
	 */
	REJECTED,

	/** Czeka na administrację platformy, czyli drugą instancję. */
	ESCALATED,

	/**
	 * Administracja utrzymała ban. To jest koniec drabiny: nad administracją nie ma
	 * nikogo, więc dalszej instancji nie ma i nie będzie.
	 */
	ESCALATION_REJECTED
}
