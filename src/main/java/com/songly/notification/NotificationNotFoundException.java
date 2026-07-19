package com.songly.notification;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

class NotificationNotFoundException extends ApiException {

	NotificationNotFoundException() {
		super(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Powiadomienie nie zostało znalezione");
	}
}
