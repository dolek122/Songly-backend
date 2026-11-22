package com.songly.nowplaying;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.now-playing")
class NowPlayingProperties {

	private String tokenEncryptionKey;
	private Duration cacheTtl = Duration.ofSeconds(45);
	private Duration oauthStateTtl = Duration.ofMinutes(10);
	private Duration oauthTicketTtl = Duration.ofMinutes(5);
	private Duration manualStatusTtl = Duration.ofHours(3);
	private Duration announcedTtl = Duration.ofHours(6);
	private String connectResultUrl = "songly://now-playing/spotify";
	private final Spotify spotify = new Spotify();
	private final Stream stream = new Stream();
	private final Poll poll = new Poll();

	String getTokenEncryptionKey() {
		return tokenEncryptionKey;
	}

	void setTokenEncryptionKey(String tokenEncryptionKey) {
		this.tokenEncryptionKey = tokenEncryptionKey;
	}

	Duration getCacheTtl() {
		return cacheTtl;
	}

	void setCacheTtl(Duration cacheTtl) {
		this.cacheTtl = cacheTtl;
	}

	Duration getOauthStateTtl() {
		return oauthStateTtl;
	}

	void setOauthStateTtl(Duration oauthStateTtl) {
		this.oauthStateTtl = oauthStateTtl;
	}

	Duration getOauthTicketTtl() {
		return oauthTicketTtl;
	}

	void setOauthTicketTtl(Duration oauthTicketTtl) {
		this.oauthTicketTtl = oauthTicketTtl;
	}

	Duration getManualStatusTtl() {
		return manualStatusTtl;
	}

	void setManualStatusTtl(Duration manualStatusTtl) {
		this.manualStatusTtl = manualStatusTtl;
	}

	Duration getAnnouncedTtl() {
		return announcedTtl;
	}

	void setAnnouncedTtl(Duration announcedTtl) {
		this.announcedTtl = announcedTtl;
	}

	String getConnectResultUrl() {
		return connectResultUrl;
	}

	void setConnectResultUrl(String connectResultUrl) {
		this.connectResultUrl = connectResultUrl;
	}

	Spotify getSpotify() {
		return spotify;
	}

	Stream getStream() {
		return stream;
	}

	Poll getPoll() {
		return poll;
	}

	static class Stream {

		private Duration timeout = Duration.ofMinutes(30);
		private int maxPerUser = 3;

		Duration getTimeout() {
			return timeout;
		}

		void setTimeout(Duration timeout) {
			this.timeout = timeout;
		}

		int getMaxPerUser() {
			return maxPerUser;
		}

		void setMaxPerUser(int maxPerUser) {
			this.maxPerUser = maxPerUser;
		}
	}

	static class Poll {

		private Duration lockTtl = Duration.ofSeconds(25);
		private Duration providerBackoff = Duration.ofMinutes(1);

		Duration getLockTtl() {
			return lockTtl;
		}

		void setLockTtl(Duration lockTtl) {
			this.lockTtl = lockTtl;
		}

		Duration getProviderBackoff() {
			return providerBackoff;
		}

		void setProviderBackoff(Duration providerBackoff) {
			this.providerBackoff = providerBackoff;
		}
	}

	static class Spotify {

		private String clientId;
		private String clientSecret;
		private String redirectUri;
		private String authorizeUrl = "https://accounts.spotify.com/authorize";
		private String tokenUrl = "https://accounts.spotify.com/api/token";
		private String apiBaseUrl = "https://api.spotify.com/v1";
		private Duration connectTimeout = Duration.ofSeconds(5);
		private Duration readTimeout = Duration.ofSeconds(10);

		String getClientId() {
			return clientId;
		}

		void setClientId(String clientId) {
			this.clientId = clientId;
		}

		String getClientSecret() {
			return clientSecret;
		}

		void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		String getRedirectUri() {
			return redirectUri;
		}

		void setRedirectUri(String redirectUri) {
			this.redirectUri = redirectUri;
		}

		String getAuthorizeUrl() {
			return authorizeUrl;
		}

		void setAuthorizeUrl(String authorizeUrl) {
			this.authorizeUrl = authorizeUrl;
		}

		String getTokenUrl() {
			return tokenUrl;
		}

		void setTokenUrl(String tokenUrl) {
			this.tokenUrl = tokenUrl;
		}

		String getApiBaseUrl() {
			return apiBaseUrl;
		}

		void setApiBaseUrl(String apiBaseUrl) {
			this.apiBaseUrl = apiBaseUrl;
		}

		Duration getConnectTimeout() {
			return connectTimeout;
		}

		void setConnectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
		}

		Duration getReadTimeout() {
			return readTimeout;
		}

		void setReadTimeout(Duration readTimeout) {
			this.readTimeout = readTimeout;
		}
	}
}
