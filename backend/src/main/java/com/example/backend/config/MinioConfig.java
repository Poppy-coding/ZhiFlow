package com.example.backend.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class MinioConfig {

    @Value("${storage.minio.endpoint}")
    private String endpoint;

    @Value("${storage.minio.accessKey}")
    private String accessKey;

    @Value("${storage.minio.secretKey}")
    private String secretKey;

    private static final String BUCKET_NAME = "zhiflow";

    @Value("${storage.minio.publicRead:false}")
    private boolean publicRead;

    @Bean
    public MinioClient minioClient() {
        try {
            MinioClient minioClient = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            //创建bucket
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET_NAME).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET_NAME).build());
                log.info("minio_bucket_created bucket={}", BUCKET_NAME);
            }
            //看是否可读
            if (publicRead) {
                log.warn("minio_public_read_ignored bucket={}", BUCKET_NAME);
            }
            return minioClient;
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 初始化失败", e);
        }
    }
}
