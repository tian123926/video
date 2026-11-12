package com.vod.main.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 轻量 Elasticsearch 客户端：直接调用 ES REST，避免引入与 ES 版本绑定的 SDK。
 */
@Slf4j
@Component
public class ElasticsearchGateway {

    private static final String INDEX_MAPPING = """
            {
              "settings": {"number_of_shards": 1, "number_of_replicas": 0},
              "mappings": {
                "properties": {
                  "id": {"type": "long"},
                  "userId": {"type": "long"},
                  "title": {"type": "text", "analyzer": "standard",
                            "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
                  "description": {"type": "text", "analyzer": "standard"},
                  "authorName": {"type": "text", "analyzer": "standard",
                                 "fields": {"keyword": {"type": "keyword", "ignore_above": 128}}},
                  "coverBucket": {"type": "keyword"},
                  "coverObject": {"type": "keyword"},
                  "viewCount": {"type": "long"},
                  "likeCount": {"type": "long"},
                  "commentCount": {"type": "long"},
                  "durationMs": {"type": "long"},
                  "publishTime": {"type": "date", "format": "epoch_millis||strict_date_optional_time"},
                  "status": {"type": "integer"},
                  "deleted": {"type": "integer"}
                }
              }
            }
            """;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String index;

    private volatile boolean available;
    private volatile long lastCheckAt;

    public ElasticsearchGateway(ObjectMapper objectMapper,
                                @Value("${vod.search.elasticsearch-url:http://127.0.0.1:9200}") String baseUrl,
                                @Value("${vod.search.index:vod_video}") String index) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.index = index;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(6000);
        this.restTemplate.setRequestFactory(factory);
        // ES 响应头通常不带 charset，统一按 UTF-8 解码，避免中文乱码
        StringHttpMessageConverter textConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        textConverter.setDefaultCharset(StandardCharsets.UTF_8);
        this.restTemplate.getMessageConverters().add(0, textConverter);
    }

    /** 30 秒内复用健康检查结果，避免每次检索都探测集群 */
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < 30_000L) {
            return available;
        }
        lastCheckAt = now;
        try {
            restTemplate.exchange(baseUrl + "/_cluster/health", HttpMethod.GET, null, String.class);
            ensureIndex();
            available = true;
        } catch (Exception ex) {
            available = false;
            log.debug("Elasticsearch 不可用: {}", ex.getMessage());
        }
        return available;
    }

    public void ensureIndex() {
        try {
            restTemplate.exchange(baseUrl + "/" + index, HttpMethod.HEAD, null, Void.class);
            return;
        } catch (HttpClientErrorException.NotFound ignored) {
            // 索引不存在，继续创建
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(baseUrl + "/" + index, HttpMethod.PUT,
                new HttpEntity<>(INDEX_MAPPING, headers), String.class);
        log.info("已创建 Elasticsearch 索引 {}", index);
    }

    public void indexDocument(Map<String, Object> document) {
        Object id = document.get("id");
        if (id == null) {
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 局部更新：数据库变更只覆盖业务字段，保留 AI 管线写入的 aiStatus/frameCount 等字段
        Map<String, Object> body = Map.of("doc", document, "doc_as_upsert", true);
        restTemplate.exchange(baseUrl + "/" + index + "/_doc/" + id + "/_update", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    public void deleteDocument(long id) {
        try {
            restTemplate.exchange(baseUrl + "/" + index + "/_doc/" + id, HttpMethod.DELETE, null, String.class);
        } catch (HttpClientErrorException.NotFound ignored) {
            // 文档本来就不存在
        }
    }

    public void bulkIndex(List<Map<String, Object>> documents) {
        if (documents.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        try {
            for (Map<String, Object> document : documents) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("_index", index);
                meta.put("_id", document.get("id"));
                body.append(objectMapper.writeValueAsString(Map.of("index", meta))).append('\n');
                body.append(objectMapper.writeValueAsString(document)).append('\n');
            }
        } catch (Exception ex) {
            throw new IllegalStateException("构建 ES bulk 请求失败", ex);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/x-ndjson"));
        // 必须用 UTF-8 字节发送，否则中文会被 String 转换器按 ISO-8859-1 写成 '?'
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        String response = restTemplate.exchange(baseUrl + "/_bulk?refresh=false", HttpMethod.POST,
                new HttpEntity<>(payload, headers), String.class).getBody();
        if (response != null && response.contains("\"errors\":true")) {
            log.warn("ES bulk 部分写入失败: {}", abbreviate(response));
        }
    }

    public void refresh() {
        restTemplate.exchange(baseUrl + "/" + index + "/_refresh", HttpMethod.POST, null, String.class);
    }

    public long count() {
        try {
            String body = restTemplate.exchange(baseUrl + "/" + index + "/_count",
                    HttpMethod.GET, null, String.class).getBody();
            return objectMapper.readTree(body == null ? "{}" : body).path("count").asLong(0);
        } catch (Exception ex) {
            return -1;
        }
    }

    /**
     * 多字段检索：标题权重 3、作者 2、简介 1，按 sort 排序，返回命中的视频 ID。
     */
    public EsHits search(String keyword, int from, int size, String sort) {
        Map<String, Object> must = Map.of("multi_match", Map.of(
                "query", keyword,
                "fields", List.of("title^3", "authorName^2", "description"),
                "type", "best_fields",
                "operator", "or"));
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", List.of(
                Map.of("term", Map.of("status", 3)),
                Map.of("term", Map.of("deleted", 0))));
        bool.put("must", List.of(must));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of("bool", bool));
        body.put("from", from);
        body.put("size", size);
        body.put("sort", sortClause(sort));
        body.put("track_total_hits", true);
        body.put("_source", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String response = restTemplate.exchange(baseUrl + "/" + index + "/_search", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody();

        List<Long> ids = new ArrayList<>();
        long total = 0;
        try {
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            total = root.path("hits").path("total").path("value").asLong(0);
            for (JsonNode hit : root.path("hits").path("hits")) {
                String id = hit.path("_id").asText("");
                if (!id.isEmpty()) {
                    try {
                        ids.add(Long.parseLong(id));
                    } catch (NumberFormatException ignored) {
                        // 非数字 ID 忽略
                    }
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("解析 ES 检索结果失败", ex);
        }
        return new EsHits(total, ids);
    }

    /** 标题 / 作者前缀联想，返回标题与作者名 */
    public List<String> suggest(String keyword, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", limit);
        body.put("_source", List.of("title", "authorName"));
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", List.of(
                Map.of("term", Map.of("status", 3)),
                Map.of("term", Map.of("deleted", 0))));
        bool.put("should", List.of(
                Map.of("match_phrase_prefix", Map.of("title", Map.of("query", keyword))),
                Map.of("match_phrase_prefix", Map.of("authorName", Map.of("query", keyword)))));
        bool.put("minimum_should_match", 1);
        body.put("query", Map.of("bool", bool));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String response = restTemplate.exchange(baseUrl + "/" + index + "/_search", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody();
        LinkedHashSet<String> words = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            for (JsonNode hit : root.path("hits").path("hits")) {
                String title = hit.path("_source").path("title").asText("");
                String author = hit.path("_source").path("authorName").asText("");
                if (!title.isBlank()) {
                    words.add(title);
                }
                if (!author.isBlank()) {
                    words.add(author);
                }
            }
        } catch (Exception ex) {
            log.debug("解析 ES 联想失败: {}", ex.getMessage());
        }
        return new ArrayList<>(words);
    }

    private List<Object> sortClause(String sort) {
        String mode = sort == null ? "score" : sort.trim().toLowerCase();
        if ("views".equals(mode) || "hottest".equals(mode)) {
            return List.of(
                    Map.of("viewCount", Map.of("order", "desc", "missing", "_last")),
                    "_score");
        }
        if ("latest".equals(mode) || "newest".equals(mode)) {
            return List.of(
                    Map.of("publishTime", Map.of("order", "desc", "missing", "_last")),
                    "_score");
        }
        return List.of("_score", Map.of("publishTime", Map.of("order", "desc", "missing", "_last")));
    }

    private static String abbreviate(String value) {
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    public record EsHits(long total, List<Long> ids) {
    }
}
