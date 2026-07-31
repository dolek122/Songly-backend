package com.songly.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Magazyn zgodny z S3: AWS S3, Cloudflare R2 albo MinIO (lokalnie i w testach).
 * Jedna implementacja na wszystkie trzy — protokół jest ten sam, więc druga
 * byłaby tylko dodatkową ścieżką kodu, której nikt nie uruchamia na produkcji.
 */
class S3ObjectStorage implements ObjectStorage {

	private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

	private final S3Client client;
	private final StorageProperties properties;

	S3ObjectStorage(S3Client client, StorageProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	@Override
	public void put(String key, byte[] content, String contentType) {
		try {
			client.putObject(PutObjectRequest.builder().bucket(properties.getBucket()).key(key).contentType(contentType)
					.contentLength((long) content.length).build(), RequestBody.fromBytes(content));
		} catch (SdkException ex) {
			throw new ObjectStorageException("Nie udało się zapisać pliku", ex);
		}
	}

	/**
	 * Kasowanie nie przerywa wołającego. Osierocony plik kosztuje ułamek grosza i
	 * daje się posprzątać później; przerwana operacja domenowa (usunięcie konta,
	 * podmiana avatara) zostawia użytkownika w stanie, którego nie prosił i z
	 * którego sam nie wyjdzie. S3 zresztą traktuje kasowanie nieistniejącego klucza
	 * jako sukces, więc realne błędy to wyłącznie awarie po tamtej stronie.
	 */
	@Override
	public void delete(String key) {
		try {
			client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
		} catch (SdkException ex) {
			log.warn("Nie udało się skasować pliku {} — zostaje osierocony: {}", key, ex.toString());
		}
	}

	@Override
	public String publicUrl(String key) {
		return properties.getPublicBaseUrl() + "/" + key;
	}
}
