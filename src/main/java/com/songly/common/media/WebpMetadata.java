package com.songly.common.media;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Usuwa metadane z WebP, przepisując kontener RIFF blok po bloku, bez
 * dekodowania obrazu.
 *
 * <p>
 * Lista dozwolonych: obraz ({@code VP8 }, {@code VP8L}, {@code ALPH}),
 * animacja, profil kolorów i nagłówek {@code VP8X}. Giną {@code EXIF} (poza
 * orientacją), {@code XMP } i bloki nieznane. Nagłówek {@code VP8X} deklaruje
 * flagami, które bloki są w pliku, więc po ich usunięciu flagi trzeba poprawić
 * — inaczej plik obiecywałby metadane, których nie ma.
 */
final class WebpMetadata {

	private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
	private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

	private static final Set<String> KEPT = Set.of("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP");

	private static final int FLAG_XMP = 0x04;
	private static final int FLAG_EXIF = 0x08;

	private WebpMetadata() {
	}

	static byte[] strip(byte[] in) {
		if (!Bytes.startsWith(in, 0, in.length, RIFF) || !Bytes.startsWith(in, 8, in.length, WEBP)) {
			throw new UnsupportedImageException();
		}
		long riffSize = Bytes.u32le(in, 4);
		if (riffSize < 4 || riffSize > in.length - 8) {
			throw new UnsupportedImageException();
		}
		// Rozmiar z nagłówka wyznacza koniec pliku — to, co doklejono za nim, ginie.
		int end = 8 + (int) riffSize;
		ByteArrayOutputStream chunks = new ByteArrayOutputStream(in.length);
		int vp8xFlagsAt = -1;
		boolean orientationHandled = false;
		boolean orientationWritten = false;
		int pos = 12;
		while (pos < end) {
			if (pos + 8 > end) {
				throw new UnsupportedImageException();
			}
			String fourCc = Bytes.ascii(in, pos, 4);
			long size = Bytes.u32le(in, pos + 4);
			if (size > end - pos - 8) {
				throw new UnsupportedImageException();
			}
			int payload = pos + 8;
			if (fourCc.equals("VP8X")) {
				if (pos != 12 || size < 10) {
					throw new UnsupportedImageException();
				}
				vp8xFlagsAt = chunks.size() + 8;
			}
			if (fourCc.equals("EXIF")) {
				if (!orientationHandled && vp8xFlagsAt >= 0) {
					orientationHandled = true;
					int orientation = ExifOrientation.read(in, payload, (int) size);
					if (orientation != ExifOrientation.NORMAL) {
						writeChunk(chunks, "EXIF", ExifOrientation.minimalTiff(orientation));
						orientationWritten = true;
					}
				}
			} else if (KEPT.contains(fourCc)) {
				writeChunk(chunks, fourCc, in, payload, (int) size);
			}
			// Blok o nieparzystym rozmiarze ma bajt wyrównania; ostatniemu bywa go brak.
			pos = (int) Math.min(end, payload + size + (size & 1));
		}
		byte[] body = chunks.toByteArray();
		if (vp8xFlagsAt >= 0) {
			int flags = body[vp8xFlagsAt] & ~FLAG_XMP & ~FLAG_EXIF;
			body[vp8xFlagsAt] = (byte) (orientationWritten ? flags | FLAG_EXIF : flags);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 12);
		out.writeBytes(RIFF);
		out.writeBytes(littleEndian(body.length + 4));
		out.writeBytes(WEBP);
		out.writeBytes(body);
		return out.toByteArray();
	}

	private static void writeChunk(ByteArrayOutputStream out, String fourCc, byte[] data) {
		writeChunk(out, fourCc, data, 0, data.length);
	}

	private static void writeChunk(ByteArrayOutputStream out, String fourCc, byte[] data, int offset, int length) {
		out.writeBytes(fourCc.getBytes(StandardCharsets.US_ASCII));
		out.writeBytes(littleEndian(length));
		out.write(data, offset, length);
		if ((length & 1) == 1) {
			out.write(0);
		}
	}

	private static byte[] littleEndian(int value) {
		return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
	}
}
