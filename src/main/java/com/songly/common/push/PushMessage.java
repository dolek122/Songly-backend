package com.songly.common.push;

import java.util.Map;

public record PushMessage(String token, String title, String body, Map<String, String> data) {

	public PushMessage {
		data = Map.copyOf(data);
	}
}
