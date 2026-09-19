package com.fixup.media.infrastructure.storage;

import com.fixup.media.application.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectStorage.class)
    public ObjectStorage objectStorage(
            @Value("${fixup.storage.endpoint:http://localhost:9000}") String endpoint,
            @Value("${fixup.storage.public-endpoint:${fixup.storage.endpoint:http://localhost:9000}}") String publicEndpoint,
            @Value("${fixup.storage.access-key}") String accessKey,
            @Value("${fixup.storage.secret-key}") String secretKey,
            @Value("${fixup.storage.bucket:fixup-private}") String bucket,
            @Value("${fixup.storage.region:us-east-1}") String region,
            @Value("${fixup.storage.auto-create-bucket:false}") boolean autoCreateBucket) {
        return new S3ObjectStorage(endpoint, publicEndpoint, accessKey, secretKey, bucket, region, autoCreateBucket);
    }
}
