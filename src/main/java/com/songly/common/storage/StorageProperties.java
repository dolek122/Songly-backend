package com.songly.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.storage")
class StorageProperties {

	private String bucket = "songly-media";

	private String region = "auto";

	/**
	 * Adres API magazynu. Puste znaczy „prawdziwe AWS S3" — SDK sam złoży wtedy
	 * endpoint z regionu. Lokalnie i w testach wskazuje na MinIO, na produkcji na
	 * Cloudflare R2.
	 */
	private String endpoint = "";

	private String accessKey = "";

	private String secretKey = "";

	/**
	 * Prefiks publicznych adresów plików, bez końcowego ukośnika.
	 *
	 * <p>
	 * Celowo osobny od {@link #endpoint}: przy R2 czy CloudFroncie klienci czytają
	 * przez domenę CDN-u, a my wgrywamy przez API. Sklejanie jednego z drugiego
	 * działałoby tylko w konfiguracji deweloperskiej i wysypało się dokładnie tam,
	 * gdzie sprawdzenie jest najtrudniejsze.
	 */
	private String publicBaseUrl = "http://localhost:9000/songly-media";

	/**
	 * Wymuszenie „path style" ({@code host/bucket/klucz}) zamiast wirtualnych
	 * hostów ({@code bucket.host/klucz}). MinIO lokalnie nie ma wildcardowego DNS,
	 * więc bez tego nie da się go w ogóle użyć.
	 */
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
