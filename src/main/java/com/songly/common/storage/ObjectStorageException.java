package com.songly.common.storage;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Awaria magazynu obiektów.
 *
 * <p>
 * 502, nie 500 — tak samo jak przy błędach Spotify (Claude.md §7). Niedostępny
 * S3 to awaria cudzego systemu, a nie nasz błąd; zrównanie ich sprawia, że
 * monitoring 500-tek przestaje odróżniać „mamy buga" od „dostawca ma awarię".
 */
public class ObjectStorageException extends ApiException {

	public ObjectStorageException(String message, Throwable cause) {
		super(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", message);
		initCause(cause);
	}
}
