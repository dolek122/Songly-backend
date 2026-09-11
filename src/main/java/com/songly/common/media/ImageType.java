package com.songly.common.media;

import java.util.Arrays;
import java.util.Optional;

public enum ImageType {

	JPEG("image/jpeg", "jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),

	PNG("image/png", "png", new byte[]{(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A}),

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
