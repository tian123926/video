package com.vod.transcode.client;

import com.vod.common.api.ApiResult;
import com.vod.common.constant.VideoConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * minio 微服务内部接口客户端：长期签名地址、流式下载、产物上传。
 */
@Slf4j
@Component
public class MinioInternalClient {

    private static final ParameterizedTypeReference<ApiResult<Object>> UPLOAD_RESULT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String serviceToken;

    public MinioInternalClient(RestTemplateBuilder restTemplateBuilder,
                               @Value("${vod.minio-service.base-url:http://127.0.0.1:8081}") String baseUrl,
                               @Value("${vod.service-token:vod-internal-token}") String serviceToken) {
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(30))
                .build();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.serviceToken = serviceToken;
    }

    /**
     * 通过内部接口流式下载到本地文件（集群内可达，不依赖对外预签名地址）。
     */
    public void downloadObject(String bucket, String objectName, Path target) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/download")
                .queryParam("bucket", bucket)
                .queryParam("objectName", objectName)
                .build()
                .encode()
                .toUri();
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("创建临时目录失败: " + target.getParent(), e);
        }
        restTemplate.execute(uri, HttpMethod.GET,
                request -> request.getHeaders().set(VideoConstants.HEADER_SERVICE_TOKEN, serviceToken),
                response -> {
                    Files.copy(response.getBody(), target, StandardCopyOption.REPLACE_EXISTING);
                    return null;
                });
        log.info("源文件下载完成: {} ({} bytes)", target, sizeOf(target));
    }

    /**
     * 通过内部 multipart 接口上传单个对象。
     */
    public void upload(String bucket, String objectName, Path file, String contentType) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/upload").build().encode().toUri();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("bucket", bucket);
        body.add("objectName", objectName);

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        body.add("file", new HttpEntity<>(new FileSystemResource(file), fileHeaders));

        HttpHeaders headers = serviceHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ApiResult<Object> result = restTemplate.exchange(uri, HttpMethod.POST,
                new HttpEntity<>(body, headers), UPLOAD_RESULT_TYPE).getBody();
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("上传对象失败 bucket=" + bucket + ", object=" + objectName + ": "
                    + (result == null ? "空响应" : result.getCode() + " " + result.getMessage()));
        }
        log.info("上传产物成功 bucket={} object={}", bucket, objectName);
    }

    private HttpHeaders serviceHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(VideoConstants.HEADER_SERVICE_TOKEN, serviceToken);
        return headers;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }
}
