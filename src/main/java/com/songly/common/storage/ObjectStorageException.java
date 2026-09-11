package com.songly.common.storage;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

public class ObjectStorageException extends ApiException {

	public ObjectStorageException(String message, Throwable cause) {
		super(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", message);
		initCause(cause);
	}
}
