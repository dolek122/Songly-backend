package com.songly.common.media;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Przesłane bajty nie są obrazem w żadnym z obsługiwanych formatów.
 *
 * <p>
 * Komunikat nie zdradza, <i>co</i> rozpoznaliśmy zamiast obrazu — przy pliku
 * podszywającym się pod obraz to informacja wyłącznie dla przesyłającego, a ten
 * i tak wie, co wysłał.
 */
public class UnsupportedImageException extends ApiException {

	public UnsupportedImageException() {
		super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_IMAGE", "Obsługiwane formaty obrazu to JPEG, PNG i WebP");
	}
}
