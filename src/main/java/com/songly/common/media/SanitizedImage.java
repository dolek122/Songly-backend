package com.songly.common.media;

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
