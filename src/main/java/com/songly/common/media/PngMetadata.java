package com.songly.common.media;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Usuwa metadane z PNG, przepisując plik blok po bloku ({@code chunk}), bez
 * dekodowania obrazu.
 *
 * <p>
 * Lista dozwolonych: bloki obrazu, przezroczystości, kolorów i animacji APNG.
 * Giną {@code eXIf} (poza orientacją, patrz {@link ExifOrientation}), bloki
 * tekstowe {@code tEXt}/{@code zTXt}/{@code iTXt} — w nich programy zapisują
 * XMP, a nawet cały EXIF jako tekst — {@code tIME} i każdy blok, którego nie
 * znamy.
 */
final class PngMetadata {

	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

	private static final Set<String> KEPT = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP",
			"sBIT", "sRGB", "cICP", "mDCV", "cLLI", "bKGD", "hIST", "pHYs", "sPLT", "acTL", "fcTL", "fdAT");

	private PngMetadata() {
	}

	static byte[] strip(byte[] in) {
		if (!Bytes.startsWith(in, 0, in.length, SIGNATURE)) {
			throw new UnsupportedImageException();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		out.writeBytes(SIGNATURE);
		boolean orientationHandled = false;
		int pos = SIGNATURE.length;
		while (true) {
			if (pos + 12 > in.length) {
				throw new UnsupportedImageException();
			}
			long length = Bytes.u32be(in, pos);
			if (length > in.length - pos - 12) {
				throw new UnsupportedImageException();
			}
			String type = Bytes.ascii(in, pos + 4, 4);
			if (pos == SIGNATURE.length && !type.equals("IHDR")) {
				throw new UnsupportedImageException();
			}
			int total = 12 + (int) length;
			if (type.equals("eXIf")) {
				if (!orientationHandled) {
					orientationHandled = true;
					int orientation = ExifOrientation.read(in, pos + 8, (int) length);
					if (orientation != ExifOrientation.NORMAL) {
						writeChunk(out, "eXIf", ExifOrientation.minimalTiff(orientation));
					}
				}
			} else if (KEPT.contains(type)) {
				out.write(in, pos, total);
			}
			pos += total;
			if (type.equals("IEND")) {
				return out.toByteArray();
			}
		}
	}

	private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
		out.writeBytes(typeBytes);
		out.writeBytes(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}
}
