package com.songly.common.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

class S3ObjectStorageTest {

	@Test
	void deleteFailure_isReportedToTheCaller() {
		S3Client client = mock(S3Client.class);
		when(client.deleteObject(any(DeleteObjectRequest.class)))
				.thenThrow(SdkClientException.create("magazyn nie odpowiada"));
		S3ObjectStorage storage = new S3ObjectStorage(client, new StorageProperties());

		assertThatThrownBy(() -> storage.delete("posts/a.png")).isInstanceOf(ObjectStorageException.class);
	}
}
