package com.songly.nowplaying;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class NowPlayingStatusSubscriber implements MessageListener {

	private static final Logger log = LoggerFactory.getLogger(NowPlayingStatusSubscriber.class);

	private final ObjectMapper objectMapper;
	private final NowPlayingStreamBroadcaster broadcaster;

	NowPlayingStatusSubscriber(ObjectMapper objectMapper, NowPlayingStreamBroadcaster broadcaster) {
		this.objectMapper = objectMapper;
		this.broadcaster = broadcaster;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		try {
			broadcaster.broadcast(objectMapper.readValue(message.getBody(), NowPlayingStatusChangedMessage.class));
		} catch (RuntimeException ex) {
			log.warn("Pomijam nieczytelną wiadomość z kanału {}: {}", NowPlayingStatusPublisher.CHANNEL, ex.toString());
		}
	}
}
