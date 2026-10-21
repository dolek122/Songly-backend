package com.songly.common.media;

/**
 * Wgrany obraz po rozpoznaniu formatu i usunięciu metadanych (ADR 0047) —
 * jedyna postać, w jakiej bajty od klienta trafiają do magazynu.
 *
 * <p>
 * Zdjęcie z aparatu niesie w EXIF-ie współrzędne GPS, model telefonu i datę co
 * do sekundy. Avatary i zdjęcia postów są publiczne z założenia (ADR 0010),
 * więc zdjęcie zrobione w domu podawało każdemu adres użytkownika. Android
 * przekodowuje zdjęcie przed wysyłką i metadane gubi po drodze, ale to
 * właściwość jednego klienta, a nie gwarancja serwera.
 *
 * <p>
 * <b>Bez przekodowania.</b> Przepisujemy kontener (segmenty JPEG-a, bloki PNG i
 * WebP), a dane obrazu kopiujemy bajt w bajt: jakość się nie zmienia, nie
 * potrzeba dekodera WebP, a plik nie jest dekompresowany do pamięci (bomba
 * dekompresyjna z 5 MB PNG to gigabajty pikseli).
 *
 * <p>
 * <b>Plik, którego struktury nie da się przeczytać, odpada</b>
 * ({@link UnsupportedImageException}). Przepuszczenie go bez zmian
 * przepuszczałoby też jego metadane.
 */
public record SanitizedImage(ImageType type, byte[] content) {

	public static SanitizedImage from(byte[] upload) {
		ImageType type = ImageType.detect(upload).orElseThrow(UnsupportedImageException::new);
		byte[] stripped = switch (type) {
			case JPEG -> JpegMetadata.strip(upload);
			case PNG -> PngMetadata.strip(upload);
			case WEBP -> WebpMetadata.strip(upload);
		};
		return new SanitizedImage(type, stripped);
	}
}
