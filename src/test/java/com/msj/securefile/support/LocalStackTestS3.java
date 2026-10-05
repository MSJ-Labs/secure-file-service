package com.msj.securefile.support;

import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;

/**
 * One LocalStack container shared by every integration test of the JVM, with the two buckets of the application
 * created. Starting a container per test class would dominate the build time. Testcontainers' Ryuk removes it on exit.
 */
public final class LocalStackTestS3 {

    public static final String QUARANTINE_BUCKET = "quarantine";
    public static final String CLEAN_BUCKET = "clean";

    // Same image as docker-compose.yml, so tests run against the runtime store.
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:4.14.0").withServices("s3");
    private static final S3Client CLIENT;

    static {
        LOCALSTACK.start();
        CLIENT = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .forcePathStyle(true)
                .build();
        CLIENT.createBucket(CreateBucketRequest.builder().bucket(QUARANTINE_BUCKET).build());
        CLIENT.createBucket(CreateBucketRequest.builder().bucket(CLEAN_BUCKET).build());
    }

    private LocalStackTestS3() {
    }

    public static S3Client client() {
        return CLIENT;
    }

    /** Removes every object of both buckets, so a test starts from an empty store. */
    public static void emptyBuckets() {
        for (String bucket : List.of(QUARANTINE_BUCKET, CLEAN_BUCKET)) {
            List<ObjectIdentifier> keys = CLIENT.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build())
                    .contents().stream()
                    .map(S3Object::key)
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList();
            if (!keys.isEmpty()) {
                CLIENT.deleteObjects(request -> request.bucket(bucket).delete(delete -> delete.objects(keys)));
            }
        }
    }
}
