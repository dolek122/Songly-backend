package com.songly.common.storage;

/**
 * Trwałe składowanie plików binarnych poza bazą relacyjną (Claude.md §4).
 *
 * <p>
 * Interfejs zna wyłącznie klucz, bajty i typ MIME — nie wie, czy przechowuje
 * avatar, zdjęcie w poście, czy cokolwiek innego (§3, reguła 3). Klucz jest
 * nieprzezroczystym identyfikatorem, którego strukturę ustala moduł domenowy.
 */
public interface ObjectStorage {

	void put(String key, byte[] content, String contentType);

	/**
	 * Kasuje obiekt. <b>Idempotentnie</b> — brak obiektu pod tym kluczem nie jest
	 * błędem. Wołający zwykle sprząta po czymś, co i tak ma zniknąć, więc wyjątek
	 * „już go nie ma" zmuszałby każde takie miejsce do łapania go bez powodu.
	 */
	void delete(String key);

	/**
	 * Publiczny adres, pod którym plik jest dostępny dla klientów. Osobny od
	 * adresu, pod który wgrywamy: przy CDN-ie (Cloudflare R2, CloudFront) to dwie
	 * różne domeny, a klienci mają dostawać tę, która stoi za cache'em.
	 */
	String publicUrl(String key);
}
