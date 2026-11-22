package com.songly.common.media;

import java.io.ByteArrayOutputStream;

final class JpegMetadata {

	private static final int SOI = 0xD8;
	private static final int EOI = 0xD9;
	private static final int SOS = 0xDA;
	private static final int APP0 = 0xE0;
	private static final int APP1 = 0xE1;
	private static final int APP2 = 0xE2;
	private static final int APP14 = 0xEE;
	private static final int APP15 = 0xEF;
	private static final int COM = 0xFE;

	private static final byte[] JFIF = {'J', 'F', 'I', 'F', 0};
	private static final byte[] EXIF = {'E', 'x', 'i', 'f', 0, 0};
	private static final byte[] ICC_PROFILE = {'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0};
	private static final byte[] ADOBE = {'A', 'd', 'o', 'b', 'e'};

	private JpegMetadata() {
	}

	static byte[] strip(byte[] in) {
		if (in.length < 4 || (in[0] & 0xFF) != 0xFF || (in[1] & 0xFF) != SOI) {
			throw new UnsupportedImageException();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		out.write(0xFF);
		out.write(SOI);
		boolean orientationHandled = false;
		int pos = 2;
		while (true) {
			if (pos >= in.length || (in[pos] & 0xFF) != 0xFF) {
				throw new UnsupportedImageException();
			}
			while (pos < in.length && (in[pos] & 0xFF) == 0xFF) {
				pos++;
			}
			if (pos >= in.length) {
				throw new UnsupportedImageException();
			}
			int marker = in[pos++] & 0xFF;
			if (marker == EOI) {
				out.write(0xFF);
				out.write(EOI);
				return out.toByteArray();
			}
			if (marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) {
				out.write(0xFF);
				out.write(marker);
				continue;
			}
			if (marker == 0x00 || marker == SOI || pos + 2 > in.length) {
				throw new UnsupportedImageException();
			}
			int length = Bytes.u16be(in, pos);
			if (length < 2 || pos + length > in.length) {
				throw new UnsupportedImageException();
			}
			int payload = pos + 2;
			int end = pos + length;
			if (marker == APP1 && Bytes.startsWith(in, payload, end, EXIF)) {
				if (!orientationHandled) {
					orientationHandled = true;
					int orientation = ExifOrientation.read(in, payload, end - payload);
					if (orientation != ExifOrientation.NORMAL) {
						writeSegment(out, APP1, ExifOrientation.jpegPayload(orientation));
					}
				}
			} else if (keeps(marker, in, payload, end)) {
				out.write(0xFF);
				out.write(marker);
				out.write(in, pos, length);
			}
			pos = end;
			if (marker == SOS) {
				int scanEnd = scanEnd(in, pos);
				out.write(in, pos, scanEnd - pos);
				pos = scanEnd;
			}
		}
	}

	private static boolean keeps(int marker, byte[] in, int payload, int end) {
		if (marker == APP0) {
			return Bytes.startsWith(in, payload, end, JFIF) && end - payload >= 14 && in[payload + 12] == 0
					&& in[payload + 13] == 0;
		}
		if (marker == APP2) {
			return Bytes.startsWith(in, payload, end, ICC_PROFILE);
		}
		if (marker == APP14) {
			return Bytes.startsWith(in, payload, end, ADOBE);
		}
		return !(marker >= APP0 && marker <= APP15) && marker != COM;
	}

	private static int scanEnd(byte[] in, int pos) {
		while (pos + 1 < in.length) {
			if ((in[pos] & 0xFF) == 0xFF) {
				int next = in[pos + 1] & 0xFF;
				if (next == 0x00 || next >= 0xD0 && next <= 0xD7) {
					pos += 2;
					continue;
				}
				return pos;
			}
			pos++;
		}
		throw new UnsupportedImageException();
	}

	private static void writeSegment(ByteArrayOutputStream out, int marker, byte[] payload) {
		int length = payload.length + 2;
		out.write(0xFF);
		out.write(marker);
		out.write(length >> 8);
		out.write(length & 0xFF);
		out.writeBytes(payload);
	}
}
