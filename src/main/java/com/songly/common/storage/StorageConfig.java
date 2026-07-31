package com.songly.common.storage;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfig {

	@Bean
	S3Client s3Client(StorageProperties properties) {
		var builder = S3Client.builder().httpClient(UrlConnectionHttpClient.create())
				.region(Region.of(properties.getRegion()))
				.serviceConfiguration(
						S3Configuration.builder().pathStyleAccessEnabled(properties.isPathStyleAccess()).build())
				.credentialsProvider(StaticCredentialsProvider
						.create(AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())));
		// Pusty endpoint znaczy prawdziwe AWS — SDK złoży adres z regionu sam. Nie da
		// się tego wyrazić przez URI.create(""), stąd warunek zamiast wartości
		// domyślnej.
		if (!properties.getEndpoint().isBlank()) {
			builder.endpointOverride(URI.create(properties.getEndpoint()));
		}
		return builder.build();
	}

	@Bean
	ObjectStorage objectStorage(S3Client s3Client, StorageProperties properties) {
		return new S3ObjectStorage(s3Client, properties);
	}
}
