package com.songly.common.push;

import java.util.Map;

/**
 * Wiadomość push. Tytuł i treść jadą w {@code data}, nie w bloku
 * {@code notification} FCM: aplikacja sama buduje powiadomienie, więc ma
 * kontrolę nad kanałem i nad tym, co otwiera stuknięcie, także w tle.
 */
public record PushMessage(String token, String title, String body, Map<String, String> data) {

	public PushMessage {
		data = Map.copyOf(data);
	}
}
