package com.songly.common.storage;

public interface ObjectStorage {

	void put(String key, byte[] content, String contentType);

	void delete(String key);

	String publicUrl(String key);
}
