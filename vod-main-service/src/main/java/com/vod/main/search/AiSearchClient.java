package com.vod.main.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 vod-ai-service：多模态混合检索与视频理解任务提交。
 */
@Slf4j
@Component
public class AiSearchClient {

    public record AiSearchResult(List<Long> ids, String cursor, boolean hasMore, String intent, long total) {
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final String baseUrl;

    public AiSearchClient(@Value("${vod.ai.enabled:false}") boolean enabled,
                          @Value("${vod.ai.base-url:http://127.0.0.1:8095}") String baseUrl) {
        this.enabled = enabled;
        String url = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        // 首次检索会触发 CLIP/文本向量模型冷加载（CPU 约 60 秒），读超时需放宽
        factory.setReadTimeout(90000);
        this.restTemplate = new RestTemplate(factory);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 混合检索，失败返回 null 由调用方回退 ES。
     */
    public AiSearchResult search(String keyword, String cursor, int size) {
        if (!enabled) {
            return null;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("keyword", keyword);
            body.put("size", size);
            if (cursor != null && !cursor.isBlank()) {
                body.put("cursor", cursor);
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String json = restTemplate.postForObject(
                    baseUrl + "/ai/search", new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(json == null ? "{}" : json);
            List<Long> ids = new ArrayList<>();
            for (JsonNode item : root.path("items")) {
                ids.add(item.path("videoId").asLong());
            }
            return new AiSearchResult(ids, text(root, "cursor"), root.path("hasMore").asBoolean(false),
                    text(root, "intent"), root.path("total").asLong(ids.size()));
        } catch (Exception ex) {
            log.warn("AI 混合检索失败，回退 ES: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 提交视频理解任务（抽帧 + 字幕），异步执行。
     */
    public void submitProcess(long videoId) {
        if (!enabled) {
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(baseUrl + "/ai/process",
                    new HttpEntity<>(Map.of("videoId", videoId), headers), String.class);
        } catch (Exception ex) {
            log.debug("提交 AI 处理失败 video={}: {}", videoId, ex.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
