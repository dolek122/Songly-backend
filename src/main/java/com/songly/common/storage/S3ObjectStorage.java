package com.songly.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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
