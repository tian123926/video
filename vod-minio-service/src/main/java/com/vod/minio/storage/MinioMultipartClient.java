package com.vod.minio.storage;

import com.google.common.collect.Multimap;
import io.minio.CreateMultipartUploadResponse;
import io.minio.ListPartsResponse;
import io.minio.S3Base;
import io.minio.credentials.StaticProvider;
import io.minio.messages.Part;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

import java.util.List;

/**
 * 基于 SDK S3Base 的分片上传封装（当前依赖的 minio 包未暴露公开 multipart API）。
 */
public class MinioMultipartClient extends S3Base {

    public MinioMultipartClient(String endpoint, String accessKey, String secretKey) {
        super(parseEndpoint(endpoint), null, null, false, false, null,
                new StaticProvider(accessKey, secretKey, null), new OkHttpClient());
    }

    private static HttpUrl parseEndpoint(String endpoint) {
        HttpUrl url = HttpUrl.parse(endpoint);
        if (url == null) {
            throw new IllegalArgumentException("无效的 MinIO endpoint: " + endpoint);
        }
        return url;
    }

    public String initiateMultipartUpload(String bucket, String objectName, String contentType) throws Exception {
        Multimap<String, String> headers = newMultimap();
        if (contentType != null && !contentType.isBlank()) {
            headers.put("Content-Type", contentType);
        }
        CreateMultipartUploadResponse response =
                createMultipartUpload(bucket, null, objectName, headers, newMultimap());
        return response.result().uploadId();
    }

    public List<Part> listParts(String bucket, String objectName, String uploadId) throws Exception {
        ListPartsResponse response =
                listParts(bucket, null, objectName, null, null, uploadId, newMultimap(), newMultimap());
        return response.result().partList();
    }

    public void completeMultipartUpload(String bucket, String objectName, String uploadId, Part[] parts) throws Exception {
        completeMultipartUpload(bucket, null, objectName, uploadId, parts, newMultimap(), newMultimap());
    }

    public void abortMultipartUpload(String bucket, String objectName, String uploadId) throws Exception {
        abortMultipartUpload(bucket, null, objectName, uploadId, newMultimap(), newMultimap());
    }
}
