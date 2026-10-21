package com.songly;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * Prawdziwe, minimalne obrazy do testów wgrywania — od ADR 0047 backend czyta
 * strukturę pliku, więc same magiczne bajty już nie wystarczają — oraz
 * metadane, jakie dokleja do nich aparat.
 */
public final class TestImages {

	/** Tekst, którego nie może być w pliku po wgraniu; stoi w miejscu GPS-u. */
	public static final String SECRET = "GPS 52.2297N 21.0122E";

	private TestImages() {
	}

	public static byte[] png() {
		return encode("png");
	}

	public static byte[] jpeg() {
		return encode("jpg");
	}

	/** JPEG z blokiem EXIF zaraz po SOI, tam gdzie zapisuje go aparat. */
	public static byte[] jpegWithExif(int orientation) {
		byte[] jpeg = jpeg();
		byte[] payload = concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), tiff(orientation));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.write(0xFF);
		out.write(0xE1);
		out.write((payload.length + 2) >> 8);
		out.write((payload.length + 2) & 0xFF);
		out.writeBytes(payload);
		out.write(jpeg, 2, jpeg.length - 2);
		return out.toByteArray();
	}

	/** Wstawia blok PNG zaraz po IHDR. */
	public static byte[] pngWithChunk(String type, byte[] data) {
		byte[] png = png();
		int afterIhdr = 8 + 12 + 13;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(png, 0, afterIhdr);
		out.writeBytes(pngChunk(type, data));
		out.write(png, afterIhdr, png.length - afterIhdr);
		return out.toByteArray();
	}

	public static byte[] pngChunk(String type, byte[] data) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		return concat(ByteBuffer.allocate(4).putInt(data.length).array(), typeBytes, data,
				ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}

	/**
	 * TIFF w kolejności Intela (jak w większości telefonów): orientacja i opis
	 * obrazu z {@link #SECRET} — opis zamiast prawdziwego bloku GPS, bo test
	 * sprawdza, że z bloku nie przeszedł żaden bajt poza orientacją.
	 */
	public static byte[] tiff(int orientation) {
		byte[] secret = (SECRET + "\0").getBytes(StandardCharsets.US_ASCII);
		int ifdEntries = 2;
		int stringOffset = 8 + 2 + 12 * ifdEntries + 4;
		ByteBuffer tiff = ByteBuffer.allocate(stringOffset + secret.length).order(ByteOrder.LITTLE_ENDIAN);
		tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
		tiff.putShort((short) ifdEntries);
		tiff.putShort((short) 0x010E).putShort((short) 2).putInt(secret.length).putInt(stringOffset);
		tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
		tiff.putInt(0);
		tiff.put(secret);
		return tiff.array();
	}

	public static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}

	private static byte[] encode(String format) {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0xFF0000);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, format, out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out.toByteArray();
	}
}
