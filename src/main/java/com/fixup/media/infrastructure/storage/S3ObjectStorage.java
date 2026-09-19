package com.fixup.media.infrastructure.storage;

import com.fixup.media.application.ObjectStorage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
public class S3ObjectStorage implements ObjectStorage {
    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final String bucket;
    private final boolean autoCreateBucket;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public S3ObjectStorage(
            @Value("${fixup.storage.endpoint:http://localhost:9000}") String endpoint,
            @Value("${fixup.storage.public-endpoint:${fixup.storage.endpoint:http://localhost:9000}}") String publicEndpoint,
            @Value("${fixup.storage.access-key}") String accessKey,
            @Value("${fixup.storage.secret-key}") String secretKey,
            @Value("${fixup.storage.bucket:fixup-private}") String bucket,
            @Value("${fixup.storage.region:us-east-1}") String region,
            @Value("${fixup.storage.auto-create-bucket:false}") boolean autoCreateBucket) {
        this.bucket = bucket;
        this.autoCreateBucket = autoCreateBucket;

        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(true).build();

        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();

        this.s3Presigner = S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
    }

    @PostConstruct
    void initBucket() {
        if (!autoCreateBucket) {
            return;
        }
        try {
            boolean exists = true;
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (S3Exception notFound) {
                exists = false;
            }
            if (!exists) {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Auto-created private S3/MinIO bucket: {}", bucket);
            }
            // Configure CORS on bucket for allowed origins
            CORSRule corsRule = CORSRule.builder()
                    .allowedMethods("PUT", "GET", "HEAD")
                    .allowedOrigins("http://localhost:4200", "http://localhost", "capacitor://localhost")
                    .allowedHeaders("*")
                    .maxAgeSeconds(3600)
                    .build();
            s3Client.putBucketCors(PutBucketCorsRequest.builder()
                    .bucket(bucket)
                    .corsConfiguration(CORSConfiguration.builder().corsRules(corsRule).build())
                    .build());
            log.info("Configured MinIO CORS for bucket: {}", bucket);
        } catch (Exception ex) {
            log.warn("Could not auto-initialize bucket/CORS: {}", ex.getMessage());
        }
    }

    @PreDestroy
    void close() {
        try {
            s3Presigner.close();
            s3Client.close();
        } catch (Exception ex) {
            log.debug("Error closing S3 resources: {}", ex.getMessage());
        }
    }

    @Override
    public UploadTicket createUploadTicket(String objectKey, String contentType, long sizeBytes, Duration expiration) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .putObjectRequest(putRequest)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        return new UploadTicket(
                presigned.url().toString(),
                "PUT",
                Map.of("Content-Type", contentType),
                Instant.now().plus(expiration));
    }

    @Override
    public StoredObjectMetadata inspect(String objectKey) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            long length = head.contentLength() != null ? head.contentLength() : 0L;
            return new StoredObjectMetadata(true, length, head.contentType(), head.eTag());
        } catch (NoSuchKeyException notFound) {
            return new StoredObjectMetadata(false, 0L, null, null);
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return new StoredObjectMetadata(false, 0L, null, null);
            }
            throw ex;
        }
    }

    @Override
    public byte[] readHead(String objectKey, int maxBytes) {
        try (var stream = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .range("bytes=0-" + (maxBytes - 1))
                .build())) {
            return stream.readNBytes(maxBytes);
        } catch (NoSuchKeyException notFound) {
            return new byte[0];
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return new byte[0];
            }
            throw ex;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public ReadTicket createReadTicket(String objectKey, Duration expiration) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .getObjectRequest(getRequest)
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        return new ReadTicket(presigned.url().toString(), Instant.now().plus(expiration));
    }

    @Override
    public void delete(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }
}
