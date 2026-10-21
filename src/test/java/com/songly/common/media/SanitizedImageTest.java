package com.songly.common.media;

import static com.songly.TestImages.SECRET;
import static com.songly.TestImages.concat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.songly.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SanitizedImageTest {

	private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);

	@Test
	void cleanJpeg_passesUnchanged() {
		byte[] jpeg = TestImages.jpeg();

		SanitizedImage image = SanitizedImage.from(jpeg);

		assertThat(image.type()).isEqualTo(ImageType.JPEG);
		assertThat(image.content()).isEqualTo(jpeg);
	}

	/**
	 * Sedno: z bloku EXIF nie przechodzi nic poza orientacją — a ta przechodzi, bo
	 * bez niej portret z telefonu leżałby bokiem.
	 */
	@Test
	void jpegExif_isReducedToOrientation() throws IOException {
		SanitizedImage image = SanitizedImage.from(TestImages.jpegWithExif(6));

		assertThat(contains(image.content(), SECRET)).isFalse();
		int exif = indexOf(image.content(), EXIF_HEADER);
		assertThat(exif).isPositive();
		assertThat(ExifOrientation.read(image.content(), exif, image.content().length - exif)).isEqualTo(6);
		assertThat(decode(image.content()).getWidth()).isEqualTo(2);
	}

	@Test
	void jpegExif_withNormalOrientation_disappearsEntirely() {
		assertThat(SanitizedImage.from(TestImages.jpegWithExif(1)).content()).isEqualTo(TestImages.jpeg());
	}

	/**
	 * XMP, IPTC (APP13), komentarz, blok MPF i to, co telefon dokleja za EOI —
	 * każde z nich potrafi nieść położenie albo drugi obraz.
	 */
	@Test
	void jpegMetadataSegmentsAndTrailer_areRemoved() {
		byte[] jpeg = TestImages.jpeg();
		byte[] withMetadata = concat(Arrays.copyOfRange(jpeg, 0, 2),
				segment(0xE1, "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>" + SECRET),
				segment(0xED, "Photoshop 3.0\0" + SECRET), segment(0xFE, SECRET), segment(0xE2, "MPF\0" + SECRET),
				Arrays.copyOfRange(jpeg, 2, jpeg.length), ("ftypmp4 " + SECRET).getBytes(StandardCharsets.US_ASCII));

		assertThat(SanitizedImage.from(withMetadata).content()).isEqualTo(jpeg);
	}

	/** Profil kolorów zmienia wygląd zdjęcia, więc zostaje. */
	@Test
	void jpegIccProfile_isKept() {
		byte[] jpeg = TestImages.jpeg();
		byte[] icc = segment(0xE2, "ICC_PROFILE\0\1\1profil");
		byte[] withIcc = concat(Arrays.copyOfRange(jpeg, 0, 2), icc, Arrays.copyOfRange(jpeg, 2, jpeg.length));

		assertThat(SanitizedImage.from(withIcc).content()).isEqualTo(withIcc);
	}

	/**
	 * Same magiczne bajty to za mało: plik, którego struktury nie da się
	 * przeczytać, nie może przejść bez zmian, bo przeszłyby też jego metadane.
	 */
	@Test
	void malformedFiles_areRejected() {
		byte[] jpeg = TestImages.jpeg();
		byte[] png = TestImages.png();

		assertThatThrownBy(() -> SanitizedImage.from(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 'd', 'a'}))
				.isInstanceOf(UnsupportedImageException.class);
		assertThatThrownBy(() -> SanitizedImage.from(Arrays.copyOf(jpeg, jpeg.length - 2)))
				.isInstanceOf(UnsupportedImageException.class);
		assertThatThrownBy(() -> SanitizedImage.from(Arrays.copyOf(png, png.length - 4)))
				.isInstanceOf(UnsupportedImageException.class);
		assertThatThrownBy(() -> SanitizedImage.from(concat("RIFF".getBytes(StandardCharsets.US_ASCII), le(1000),
				"WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)))).isInstanceOf(UnsupportedImageException.class);
	}

	@Test
	void cleanPng_passesUnchanged() {
		byte[] png = TestImages.png();

		assertThat(SanitizedImage.from(png).content()).isEqualTo(png);
	}

	@Test
	void pngTextAndTimeChunks_andTrailer_areRemoved() {
		byte[] withText = TestImages.pngWithChunk("tEXt", ("Comment\0" + SECRET).getBytes(StandardCharsets.US_ASCII));
		byte[] withXmp = TestImages.pngWithChunk("iTXt",
				("XML:com.adobe.xmp\0\0\0\0\0" + SECRET).getBytes(StandardCharsets.US_ASCII));
		byte[] withTime = TestImages.pngWithChunk("tIME", new byte[]{0x07, (byte) 0xEA, 10, 21, 12, 0, 0});
		byte[] withTrailer = concat(TestImages.png(), SECRET.getBytes(StandardCharsets.US_ASCII));

		for (byte[] file : new byte[][]{withText, withXmp, withTime, withTrailer}) {
			assertThat(SanitizedImage.from(file).content()).isEqualTo(TestImages.png());
		}
	}

	@Test
	void pngExif_isReducedToOrientation_withValidChecksum() throws IOException {
		byte[] stripped = SanitizedImage.from(TestImages.pngWithChunk("eXIf", TestImages.tiff(8))).content();

		assertThat(contains(stripped, SECRET)).isFalse();
		int type = indexOf(stripped, "eXIf".getBytes(StandardCharsets.US_ASCII));
		int length = ByteBuffer.wrap(stripped, type - 4, 4).getInt();
		assertThat(ExifOrientation.read(stripped, type + 4, length)).isEqualTo(8);
		CRC32 crc = new CRC32();
		crc.update(stripped, type, 4 + length);
		assertThat(ByteBuffer.wrap(stripped, type + 4 + length, 4).getInt()).isEqualTo((int) crc.getValue());
		assertThat(decode(stripped).getWidth()).isEqualTo(2);
	}

	/**
	 * Prosty WebP nie ma gdzie trzymać metadanych, więc nic się w nim nie zmienia.
	 */
	@Test
	void simpleWebp_passesUnchanged() {
		byte[] webp = webp(chunk("VP8L", new byte[]{0x2F, 1, 2, 3, 4}));

		SanitizedImage image = SanitizedImage.from(webp);

		assertThat(image.type()).isEqualTo(ImageType.WEBP);
		assertThat(image.content()).isEqualTo(webp);
	}

	/**
	 * Flagi w VP8X muszą iść za blokami: plik bez XMP nie może go deklarować, a
	 * blok EXIF z samą orientacją — tak.
	 */
	@Test
	void extendedWebp_losesXmpAndExif_exceptOrientation_andFlagsFollow() {
		byte[] vp8x = new byte[10];
		vp8x[0] = 0x20 | 0x08 | 0x04;
		byte[] webp = webp(chunk("VP8X", vp8x), chunk("ICCP", "profil".getBytes(StandardCharsets.US_ASCII)),
				chunk("VP8L", new byte[]{0x2F, 1, 2}), chunk("EXIF", TestImages.tiff(3)),
				chunk("XMP ", SECRET.getBytes(StandardCharsets.US_ASCII)),
				chunk("ABCD", SECRET.getBytes(StandardCharsets.US_ASCII)));

		byte[] stripped = SanitizedImage.from(webp).content();

		assertThat(contains(stripped, SECRET)).isFalse();
		assertThat(stripped[20]).isEqualTo((byte) (0x20 | 0x08));
		assertThat(ByteBuffer.wrap(stripped, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt())
				.isEqualTo(stripped.length - 8);
		int exif = indexOf(stripped, "EXIF".getBytes(StandardCharsets.US_ASCII));
		int length = ByteBuffer.wrap(stripped, exif + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
		assertThat(ExifOrientation.read(stripped, exif + 8, length)).isEqualTo(3);
		assertThat(indexOf(stripped, "ICCP".getBytes(StandardCharsets.US_ASCII))).isPositive();
	}

	@Test
	void extendedWebp_withNormalOrientation_dropsExifFlag() {
		byte[] vp8x = new byte[10];
		vp8x[0] = 0x08;
		byte[] stripped = SanitizedImage
				.from(webp(chunk("VP8X", vp8x), chunk("VP8L", new byte[]{0x2F, 1}), chunk("EXIF", TestImages.tiff(1))))
				.content();

		assertThat(stripped[20]).isZero();
		assertThat(indexOf(stripped, "EXIF".getBytes(StandardCharsets.US_ASCII))).isNegative();
	}

	private static byte[] segment(int marker, String payload) {
		byte[] bytes = payload.getBytes(StandardCharsets.ISO_8859_1);
		return concat(new byte[]{(byte) 0xFF, (byte) marker, (byte) ((bytes.length + 2) >> 8),
				(byte) ((bytes.length + 2) & 0xFF)}, bytes);
	}

	private static byte[] webp(byte[]... chunks) {
		byte[] body = concat(chunks);
		return concat("RIFF".getBytes(StandardCharsets.US_ASCII), le(body.length + 4),
				"WEBP".getBytes(StandardCharsets.US_ASCII), body);
	}

	private static byte[] chunk(String fourCc, byte[] data) {
		byte[] padding = (data.length & 1) == 1 ? new byte[1] : new byte[0];
		return concat(fourCc.getBytes(StandardCharsets.US_ASCII), le(data.length), data, padding);
	}

	private static byte[] le(int value) {
		return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
	}

	private static BufferedImage decode(byte[] content) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(content));
	}

	private static boolean contains(byte[] haystack, String needle) {
		return indexOf(haystack, needle.getBytes(StandardCharsets.US_ASCII)) >= 0;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
				return i;
			}
		}
		return -1;
	}
}
