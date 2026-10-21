package com.songly.common.media;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Odczyt liczb i nazw z kontenerów obrazów, wspólny dla trzech formatów. */
final class Bytes {

	private Bytes() {
	}

	static int u16be(byte[] data, int at) {
		return (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
	}

	static long u32be(byte[] data, int at) {
		return (long) u16be(data, at) << 16 | u16be(data, at + 2);
	}

	static long u32le(byte[] data, int at) {
		return (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16
				| (data[at + 3] & 0xFFL) << 24;
	}

	static String ascii(byte[] data, int at, int length) {
		return new String(data, at, length, StandardCharsets.US_ASCII);
	}

	/** Czy {@code data[from..to)} zaczyna się od {@code prefix}. */
	static boolean startsWith(byte[] data, int from, int to, byte[] prefix) {
		return to - from >= prefix.length && Arrays.equals(data, from, from + prefix.length, prefix, 0, prefix.length);
	}
}
