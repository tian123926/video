package com.vod.main.client;

import com.vod.common.api.ApiResult;
import com.vod.common.api.ErrorCode;
import com.vod.common.constant.VideoConstants;
import com.vod.common.exception.BusinessException;
import com.vod.main.client.dto.ObjectUrlBatchRequest;
import com.vod.main.client.dto.ObjectUrlItem;
import com.vod.main.client.dto.ObjectUrlResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * 调用 minio 微服务内部接口，请求头携带 X-Service-Token。
 */
@Slf4j
@Component
public class MinioInternalClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String serviceToken;

    public MinioInternalClient(RestTemplate restTemplate,
                               @Value("${vod.minio-service.base-url:http://127.0.0.1:8081}") String baseUrl,
                               @Value("${vod.service-token:vod-internal-token}") String serviceToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceToken = serviceToken;
    }

    /**
     * 单对象签名；失败抛出 MINIO_UNAVAILABLE。
     */
    public ObjectUrlResp objectUrl(String bucket, String objectName, int expires) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/object-url")
                .queryParam("bucket", bucket)
                .queryParam("objectName", objectName)
                .queryParam("expires", expires)
                .build()
                .encode()
                .toUri();
        try {
            ResponseEntity<ApiResult<ObjectUrlResp>> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(serviceHeaders()),
                    new ParameterizedTypeReference<>() {
                    });
            ApiResult<ObjectUrlResp> body = response.getBody();
            if (body == null || !body.isSuccess() || body.getData() == null) {
                throw new BusinessException(ErrorCode.MINIO_UNAVAILABLE);
            }
            return body.getData();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("对象签名失败 bucket={} objectName={}: {}", bucket, objectName, ex.getMessage());
            throw new BusinessException(ErrorCode.MINIO_UNAVAILABLE);
        }
    }

    /**
     * 批量签名；失败降级为空列表（调用方将 URL 置 null），不阻断列表接口。
     */
    public List<ObjectUrlItem> batchObjectUrls(String bucket, List<String> objectNames, int expires) {
        if (objectNames == null || objectNames.isEmpty()) {
            return List.of();
        }
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/object-urls")
                .build()
                .encode()
                .toUri();
        HttpHeaders headers = serviceHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<ApiResult<List<ObjectUrlItem>>> response = restTemplate.exchange(
                    uri, HttpMethod.POST, new HttpEntity<>(new ObjectUrlBatchRequest(bucket, objectNames, expires), headers),
                    new ParameterizedTypeReference<>() {
                    });
            ApiResult<List<ObjectUrlItem>> body = response.getBody();
            if (body == null || !body.isSuccess() || body.getData() == null) {
                log.warn("批量对象签名返回异常 bucket={} count={}", bucket, objectNames.size());
                return List.of();
            }
            return body.getData();
        } catch (Exception ex) {
            log.warn("批量对象签名失败 bucket={} count={}: {}", bucket, objectNames.size(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 读取转码桶 m3u8 文本；失败抛出 MINIO_UNAVAILABLE。
     */
    public String hlsPlaylist(String bucket, String objectName, int expires) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/hls-playlist")
                .queryParam("bucket", bucket)
                .queryParam("objectName", objectName)
                .queryParam("expires", expires)
                .build()
                .encode()
                .toUri();
        return getText(uri, "m3u8");
    }

    /**
     * 由分片元数据动态生成 DASH 清单（MSE 播放用）。
     */
    public String generatedDashManifest(String bucket, String uid, String vid,
                                        Integer segments, Integer segmentDurationMs,
                                        Integer audioSegments, Integer audioSegmentDurationMs,
                                        Integer durationMs, String videoCodecs, String audioCodecs,
                                        Integer width, Integer height, int expires) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/generated-mpd")
                .queryParam("bucket", bucket)
                .queryParam("uid", uid)
                .queryParam("vid", vid)
                .queryParam("segments", segments == null || segments <= 0 ? 1 : segments)
                .queryParam("expires", expires);
        if (segmentDurationMs != null && segmentDurationMs > 0) builder.queryParam("segmentDurationMs", segmentDurationMs);
        if (audioSegments != null && audioSegments > 0) builder.queryParam("audioSegments", audioSegments);
        if (audioSegmentDurationMs != null && audioSegmentDurationMs > 0) {
            builder.queryParam("audioSegmentDurationMs", audioSegmentDurationMs);
        }
        if (durationMs != null && durationMs > 0) builder.queryParam("durationMs", durationMs);
        if (videoCodecs != null && !videoCodecs.isBlank()) builder.queryParam("videoCodecs", videoCodecs);
        if (audioCodecs != null && !audioCodecs.isBlank()) builder.queryParam("audioCodecs", audioCodecs);
        if (width != null && width > 0) builder.queryParam("width", width);
        if (height != null && height > 0) builder.queryParam("height", height);
        return getText(builder.build().encode().toUri(), "generated-mpd");
    }

    /**
     * 读取静态 MPD 并改写为预签名地址（兼容老转码产物）。
     */
    public String dashManifest(String bucket, String objectName, int expires) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + "/internal/dash-manifest")
                .queryParam("bucket", bucket)
                .queryParam("objectName", objectName)
                .queryParam("expires", expires)
                .build()
                .encode()
                .toUri();
        return getText(uri, "dash-manifest");
    }

    private String getText(URI uri, String label) {
        HttpHeaders headers = serviceHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN, MediaType.ALL));
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new BusinessException(ErrorCode.MINIO_UNAVAILABLE);
            }
            return response.getBody();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("读取 {} 失败 uri={}: {}", label, uri, ex.getMessage());
            throw new BusinessException(ErrorCode.MINIO_UNAVAILABLE);
        }
    }

    private HttpHeaders serviceHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(VideoConstants.HEADER_SERVICE_TOKEN, serviceToken);
        return headers;
    }
}
