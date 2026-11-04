package com.vod.minio.storage;

import com.vod.common.api.ErrorCode;
import com.vod.common.exception.BusinessException;
import com.vod.minio.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import io.minio.messages.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MinioStorage {

    private final MinioClient minioClient;
    private final MinioProperties properties;
    private final MinioMultipartClient multipartClient;

    public MinioStorage(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
        this.multipartClient = new MinioMultipartClient(
                properties.getEndpoint(), properties.getAccessKey(), properties.getSecretKey());
    }

    public void createBucketIfNotExists(String bucket) {
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception ex) {
            throw unavailable("创建桶 " + bucket + " 失败", ex);
        }
    }

    public String initiateMultipartUpload(String bucket, String objectName, String contentType) {
        try {
            return multipartClient.initiateMultipartUpload(bucket, objectName, contentType);
        } catch (Exception ex) {
            throw unavailable("初始化分片上传失败", ex);
        }
    }

    public String presignPutPart(String bucket, String objectName, String uploadId,
                                 int partNumber, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(expirySeconds)
                    .extraQueryParams(Map.of(
                            "uploadId", uploadId,
                            "partNumber", String.valueOf(partNumber)))
                    .build());
        } catch (Exception ex) {
            throw unavailable("生成分片上传签名失败", ex);
        }
    }

    public List<PartInfo> listParts(String bucket, String objectName, String uploadId) {
        try {
            List<Part> parts = multipartClient.listParts(bucket, objectName, uploadId);
            if (parts == null || parts.isEmpty()) {
                return new ArrayList<>();
            }
            List<PartInfo> result = new ArrayList<>(parts.size());
            for (Part part : parts) {
                result.add(new PartInfo(part.partNumber(), part.etag(), part.partSize()));
            }
            return result;
        } catch (Exception ex) {
            throw unavailable("查询已上传分片失败", ex);
        }
    }

    public void completeMultipart(String bucket, String objectName, String uploadId, List<PartInfo> parts) {
        try {
            Part[] array = parts.stream()
                    .map(part -> new Part(part.getPartNumber(), part.getEtag()))
                    .toArray(Part[]::new);
            multipartClient.completeMultipartUpload(bucket, objectName, uploadId, array);
        } catch (Exception ex) {
            throw unavailable("合并分片失败", ex);
        }
    }

    public void abortMultipart(String bucket, String objectName, String uploadId) {
        try {
            multipartClient.abortMultipartUpload(bucket, objectName, uploadId);
        } catch (Exception ex) {
            throw unavailable("取消分片上传失败", ex);
        }
    }

    public String presignGet(String bucket, String objectName, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(expirySeconds)
                    .build());
        } catch (Exception ex) {
            throw unavailable("生成下载签名失败", ex);
        }
    }

    public void putObject(String bucket, String objectName, byte[] content, String contentType) {
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(content), content.length, -1);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            minioClient.putObject(builder.build());
        } catch (Exception ex) {
            throw unavailable("上传对象失败", ex);
        }
    }

    public String getObjectString(String bucket, String objectName) {
        try (GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .build())) {
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw unavailable("读取对象失败", ex);
        }
    }

    public int getPresignExpirySeconds() {
        return properties.getPresignExpirySeconds();
    }

    private BusinessException unavailable(String action, Exception ex) {
        log.error("MinIO {}: {}", action, ex.getMessage(), ex);
        return new BusinessException(ErrorCode.MINIO_UNAVAILABLE);
    }
}
