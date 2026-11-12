package com.vod.minio.config;

import com.vod.common.constant.VideoConstants;
import com.vod.minio.storage.MinioStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BucketInitializer implements ApplicationRunner {

    private final MinioStorage minioStorage;

    public BucketInitializer(MinioStorage minioStorage) {
        this.minioStorage = minioStorage;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String bucket : new String[]{
                VideoConstants.BUCKET_SOURCE,
                VideoConstants.BUCKET_COVER,
                VideoConstants.BUCKET_HLS,
                VideoConstants.BUCKET_AVATAR,
                VideoConstants.BUCKET_COMMENT}) {
            try {
                minioStorage.createBucketIfNotExists(bucket);
            } catch (Exception ex) {
                log.warn("初始化 MinIO 桶 {} 失败，稍后可重试: {}", bucket, ex.getMessage());
            }
        }
    }
}
