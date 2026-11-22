package com.songly.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.storage")
class StorageProperties {

	private String bucket = "songly-media";

	private String region = "auto";

	private String endpoint = "";

	private String accessKey = "";

	private String secretKey = "";

	private String publicBaseUrl = "http://localhost:9000/songly-media";

	private boolean pathStyleAccess = true;

	String getBucket() {
		return bucket;
	}

	void setBucket(String bucket) {
		this.bucket = bucket;
	}

	String getRegion() {
		return region;
	}

	void setRegion(String region) {
		this.region = region;
	}

	String getEndpoint() {
		return endpoint;
	}

	void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	String getAccessKey() {
		return accessKey;
	}

	void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	String getSecretKey() {
		return secretKey;
	}

	void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	String getPublicBaseUrl() {
		return publicBaseUrl;
	}

	void setPublicBaseUrl(String publicBaseUrl) {
		this.publicBaseUrl = publicBaseUrl;
	}

	boolean isPathStyleAccess() {
		return pathStyleAccess;
	}

	void setPathStyleAccess(boolean pathStyleAccess) {
		this.pathStyleAccess = pathStyleAccess;
	}
}
