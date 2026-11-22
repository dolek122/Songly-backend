package com.songly.common.media;

import java.nio.ByteBuffer;

final class ExifOrientation {

	static final int NORMAL = 1;

	private static final int ORIENTATION_TAG = 0x0112;

	private static final int TYPE_SHORT = 3;

	private static final byte[] JPEG_EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};

	private ExifOrientation() {
	}

	static int read(byte[] data, int offset, int length) {
		if (Bytes.startsWith(data, offset, offset + length, JPEG_EXIF_HEADER)) {
			offset += JPEG_EXIF_HEADER.length;
			length -= JPEG_EXIF_HEADER.length;
		}
		if (length < 8) {
			return NORMAL;
		}
		boolean littleEndian;
		if (data[offset] == 'I' && data[offset + 1] == 'I') {
			littleEndian = true;
		} else if (data[offset] == 'M' && data[offset + 1] == 'M') {
			littleEndian = false;
		} else {
			return NORMAL;
		}
		Tiff tiff = new Tiff(data, offset, length, littleEndian);
		if (tiff.u16(2) != 42) {
			return NORMAL;
		}
		long ifd = tiff.u32(4);
		if (ifd < 8 || ifd + 2 > length) {
			return NORMAL;
		}
		int entries = tiff.u16((int) ifd);
		for (int i = 0; i < entries; i++) {
			int entry = (int) ifd + 2 + 12 * i;
			if (entry + 12 > length) {
				return NORMAL;
			}
			if (tiff.u16(entry) == ORIENTATION_TAG) {
				int value = tiff.u16(entry + 8);
				return tiff.u16(entry + 2) == TYPE_SHORT && value >= 1 && value <= 8 ? value : NORMAL;
			}
		}
		return NORMAL;
	}

	static byte[] minimalTiff(int orientation) {
		return ByteBuffer.allocate(26).put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8)
				.putShort((short) 1).putShort((short) ORIENTATION_TAG).putShort((short) TYPE_SHORT).putInt(1)
				.putShort((short) orientation).putShort((short) 0).putInt(0).array();
	}

	static byte[] jpegPayload(int orientation) {
		byte[] tiff = minimalTiff(orientation);
		byte[] payload = new byte[JPEG_EXIF_HEADER.length + tiff.length];
		System.arraycopy(JPEG_EXIF_HEADER, 0, payload, 0, JPEG_EXIF_HEADER.length);
		System.arraycopy(tiff, 0, payload, JPEG_EXIF_HEADER.length, tiff.length);
		return payload;
	}

	private record Tiff(byte[] data, int offset, int length, boolean littleEndian) {

		int u16(int at) {
			int a = data[offset + at] & 0xFF;
			int b = data[offset + at + 1] & 0xFF;
			return littleEndian ? a | b << 8 : a << 8 | b;
		}

		long u32(int at) {
			long high = u16(littleEndian ? at + 2 : at);
			long low = u16(littleEndian ? at : at + 2);
			return high << 16 | low;
		}
	}
}
