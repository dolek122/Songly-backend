package com.songly.common.media;

import java.util.Arrays;
import java.util.Optional;

/**
 * Rozpoznawanie formatu obrazu <b>po zawartości pliku</b>, nie po nagłówku
 * {@code Content-Type} ani rozszerzeniu nazwy.
 *
 * <p>
 * To nie jest nadgorliwość. Jedno i drugie podaje klient, więc jedno i drugie
 * jest kłamstwem, kiedy komuś na tym zależy: wystarczyłoby wysłać plik
 * wykonywalny z nagłówkiem {@code image/png}, żeby wylądował na naszym
 * publicznym adresie i był stamtąd serwowany. Magiczne bajty są jedyną
 * informacją o pliku, która pochodzi od samego pliku.
 *
 * <p>
 * Co to <b>nie</b> jest: pełną walidacją obrazu. Poprawny nagłówek PNG z
 * uszkodzoną resztą dalej przejdzie. Chodzi o odsianie plików, które obrazem
 * nie są nawet z pozoru — a nie o dekodowanie cudzych danych w naszym procesie,
 * bo to samo w sobie bywa wektorem ataku.
 */
public enum ImageType {

	JPEG("image/jpeg", "jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),

	PNG("image/png", "png", new byte[]{(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A}),

	/**
	 * WebP siedzi w kontenerze RIFF: bajty 0–3 to {@code RIFF}, 4–7 to rozmiar
	 * (zmienny), a dopiero 8–11 to {@code WEBP}. Stąd osobne sprawdzenie zamiast
	 * jednego ciągłego prefiksu.
	 */
	WEBP("image/webp", "webp", null);

	private final String contentType;
	private final String extension;
	private final byte[] magic;

	ImageType(String contentType, String extension, byte[] magic) {
		this.contentType = contentType;
		this.extension = extension;
		this.magic = magic;
	}

	public String contentType() {
		return contentType;
	}

	public String extension() {
		return extension;
	}

	public static Optional<ImageType> detect(byte[] content) {
		if (content == null) {
			return Optional.empty();
		}
		for (ImageType type : values()) {
			if (type.matches(content)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}

	private boolean matches(byte[] content) {
		if (this == WEBP) {
			return content.length >= 12 && startsWith(content, 0, new byte[]{'R', 'I', 'F', 'F'})
					&& startsWith(content, 8, new byte[]{'W', 'E', 'B', 'P'});
		}
		return startsWith(content, 0, magic);
	}

	private static boolean startsWith(byte[] content, int offset, byte[] expected) {
		if (content.length < offset + expected.length) {
			return false;
		}
		return Arrays.equals(content, offset, offset + expected.length, expected, 0, expected.length);
	}
}
