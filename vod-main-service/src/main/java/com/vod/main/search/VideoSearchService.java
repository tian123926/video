package com.vod.main.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vod.common.constant.VideoStatus;
import com.vod.main.dto.HotKeyword;
import com.vod.main.entity.SearchKeyword;
import com.vod.main.entity.User;
import com.vod.main.entity.Video;
import com.vod.main.mapper.SearchKeywordMapper;
import com.vod.main.mapper.UserMapper;
import com.vod.main.mapper.VideoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 视频检索：ES 优先，ES 不可用时回退 MySQL。
 */
@Slf4j
@Service
public class VideoSearchService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int SYNC_BATCH_SIZE = 200;
    private static final List<String> DEFAULT_HOT = List.of(
            "游戏", "动漫", "音乐", "知识", "科技", "美食", "旅行", "纪录片", "影视", "鬼畜");

    private final VideoMapper videoMapper;
    private final UserMapper userMapper;
    private final SearchKeywordMapper keywordMapper;
    private final ElasticsearchGateway gateway;
    private final AiSearchClient aiSearchClient;

    public VideoSearchService(VideoMapper videoMapper,
                              UserMapper userMapper,
                              SearchKeywordMapper keywordMapper,
                              ElasticsearchGateway gateway,
                              AiSearchClient aiSearchClient) {
        this.videoMapper = videoMapper;
        this.userMapper = userMapper;
        this.keywordMapper = keywordMapper;
        this.gateway = gateway;
        this.aiSearchClient = aiSearchClient;
    }

    public record SearchPage(List<Video> rows, long total, int page, int size, boolean hasMore, String source) {
    }

    public record CursorPage(List<Video> rows, long total, int size, boolean hasMore,
                             String cursor, String intent, String source) {
    }

    /**
     * 游标检索：优先走 vod-ai-service 的多模态混合检索（关键词/自然语言自动分流），
     * 服务不可用时回退 ES 分页检索。
     */
    public CursorPage searchByCursor(String keyword, String cursor, int size) {
        String kw = keyword == null ? "" : keyword.trim();
        int pageSize = Math.max(1, Math.min(size <= 0 ? 20 : size, MAX_PAGE_SIZE));
        if (kw.isEmpty()) {
            return new CursorPage(List.of(), 0, pageSize, false, null, "keyword", "empty");
        }
        if (aiSearchClient.enabled()) {
            AiSearchClient.AiSearchResult result = aiSearchClient.search(kw, cursor, pageSize);
            if (result != null) {
                List<Video> rows = loadOrdered(result.ids());
                return new CursorPage(rows, result.total(), pageSize, result.hasMore(),
                        result.cursor(), result.intent(), "hybrid");
            }
        }
        SearchPage fallback = search(kw, 1, pageSize, "score");
        return new CursorPage(fallback.rows(), fallback.total(), pageSize, fallback.hasMore(),
                null, "keyword", fallback.source());
    }

    /**
     * 按 AI 服务返回的 videoId 顺序回查视频。
     */
    private List<Video> loadOrdered(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Video> found = videoMapper.selectBatchIds(ids);
        Map<Long, Video> index = new HashMap<>();
        for (Video video : found) {
            index.put(video.getId(), video);
        }
        List<Video> rows = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Video video = index.get(id);
            if (video != null) {
                rows.add(video);
            }
        }
        return rows;
    }

    public SearchPage search(String keyword, int page, int size, String sort) {
        String kw = normalize(keyword);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int from = (safePage - 1) * safeSize;
        if (kw.isEmpty()) {
            return new SearchPage(List.of(), 0, safePage, safeSize, false, "empty");
        }
        if (gateway.isAvailable()) {
            try {
                ElasticsearchGateway.EsHits hits = gateway.search(kw, from, safeSize, sort);
                List<Video> rows = loadOrdered(hits.ids());
                boolean hasMore = (long) from + rows.size() < hits.total();
                return new SearchPage(rows, hits.total(), safePage, safeSize, hasMore, "es");
            } catch (Exception ex) {
                log.warn("ES 检索失败，回退数据库: {}", ex.getMessage());
            }
        }
        return searchFromDatabase(kw, safePage, safeSize);
    }

    public List<String> suggest(String keyword, int limit) {
        String kw = normalize(keyword);
        int max = Math.min(Math.max(limit, 1), 20);
        if (kw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> words = new LinkedHashSet<>();
        try {
            List<SearchKeyword> rows = keywordMapper.selectList(Wrappers.<SearchKeyword>lambdaQuery()
                    .and(w -> w.likeRight(SearchKeyword::getWord, kw)
                            .or().like(SearchKeyword::getSynonyms, kw))
                    .orderByDesc(SearchKeyword::getWeight)
                    .orderByAsc(SearchKeyword::getId)
                    .last("LIMIT " + max));
            for (SearchKeyword row : rows) {
                words.add(row.getWord());
            }
        } catch (Exception ex) {
            log.warn("检索词库查询失败: {}", ex.getMessage());
        }
        if (gateway.isAvailable() && words.size() < max) {
            try {
                words.addAll(gateway.suggest(kw, max));
            } catch (Exception ex) {
                log.debug("ES 联想失败: {}", ex.getMessage());
            }
        }
        if (words.size() < max) {
            try {
                List<Video> rows = videoMapper.selectList(Wrappers.lambdaQuery(Video.class)
                        .select(Video::getTitle)
                        .eq(Video::getStatus, VideoStatus.READY)
                        .eq(Video::getDeleted, 0)
                        .like(Video::getTitle, kw)
                        .orderByDesc(Video::getViewCount)
                        .last("LIMIT " + max));
                for (Video row : rows) {
                    if (row.getTitle() != null && !row.getTitle().isBlank()) {
                        words.add(row.getTitle());
                    }
                    if (words.size() >= max) {
                        break;
                    }
                }
            } catch (Exception ex) {
                log.debug("数据库联想失败: {}", ex.getMessage());
            }
        }
        return words.stream().limit(max).toList();
    }

    public List<HotKeyword> hot(int limit) {
        int max = Math.min(Math.max(limit, 1), 20);
        try {
            List<SearchKeyword> rows = keywordMapper.selectList(Wrappers.<SearchKeyword>lambdaQuery()
                    .eq(SearchKeyword::getIsHot, 1)
                    .orderByDesc(SearchKeyword::getWeight)
                    .orderByAsc(SearchKeyword::getId)
                    .last("LIMIT " + max));
            if (!rows.isEmpty()) {
                List<HotKeyword> items = new ArrayList<>(rows.size());
                for (SearchKeyword row : rows) {
                    items.add(new HotKeyword(row.getWord(), row.getWeight()));
                }
                return items;
            }
        } catch (Exception ex) {
            log.warn("热词查询失败: {}", ex.getMessage());
        }
        return DEFAULT_HOT.stream().limit(max).map(word -> new HotKeyword(word, 0)).toList();
    }

    /** 全量同步 READY 视频到 ES（启动时异步执行） */
    public int syncAll() {
        if (!gateway.isAvailable()) {
            log.warn("Elasticsearch 不可用，跳过视频索引全量同步");
            return 0;
        }
        long lastId = 0L;
        int indexed = 0;
        while (true) {
            List<Video> rows = videoMapper.selectList(Wrappers.lambdaQuery(Video.class)
                    .eq(Video::getStatus, VideoStatus.READY)
                    .eq(Video::getDeleted, 0)
                    .gt(Video::getId, lastId)
                    .orderByAsc(Video::getId)
                    .last("LIMIT " + SYNC_BATCH_SIZE));
            if (rows.isEmpty()) {
                break;
            }
            Map<Long, User> users = loadUsers(rows.stream().map(Video::getUserId).toList());
            List<Map<String, Object>> documents = new ArrayList<>(rows.size());
            for (Video video : rows) {
                documents.add(toDocument(video, users.get(video.getUserId())));
            }
            gateway.bulkIndex(documents);
            indexed += rows.size();
            lastId = rows.get(rows.size() - 1).getId();
        }
        gateway.refresh();
        log.info("视频索引全量同步完成：写入 {} 条，索引当前 {} 条", indexed, gateway.count());
        return indexed;
    }

    /** Canal 行变更后刷新单条索引；非就绪 / 已删除则移除索引 */
    public void refreshVideo(long videoId) {
        if (!gateway.isAvailable()) {
            return;
        }
        Video video = videoMapper.selectById(videoId);
        if (video == null
                || Integer.valueOf(1).equals(video.getDeleted())
                || !Integer.valueOf(VideoStatus.READY).equals(video.getStatus())) {
            gateway.deleteDocument(videoId);
            return;
        }
        gateway.indexDocument(toDocument(video, userMapper.selectById(video.getUserId())));
    }

    private SearchPage searchFromDatabase(String keyword, int page, int size) {
        List<Long> userIds = userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .select(User::getId)
                        .like(User::getUsername, keyword))
                .stream()
                .map(User::getId)
                .toList();
        LambdaQueryWrapper<Video> wrapper = Wrappers.lambdaQuery(Video.class)
                .eq(Video::getStatus, VideoStatus.READY)
                .eq(Video::getDeleted, 0)
                .and(w -> {
                    w.like(Video::getTitle, keyword).or().like(Video::getDescription, keyword);
                    if (!userIds.isEmpty()) {
                        w.or().in(Video::getUserId, userIds);
                    }
                });
        long total = videoMapper.selectCount(wrapper);
        wrapper.orderByDesc(Video::getId)
                .last("LIMIT " + (long) (page - 1) * size + "," + size);
        List<Video> rows = videoMapper.selectList(wrapper);
        return new SearchPage(rows, total, page, size, (long) page * size < total, "db");
    }

    private Map<Long, User> loadUsers(List<Long> userIds) {
        Map<Long, User> users = new HashMap<>();
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return users;
        }
        for (User user : userMapper.selectBatchIds(ids)) {
            users.put(user.getId(), user);
        }
        return users;
    }

    private Map<String, Object> toDocument(Video video, User user) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", video.getId());
        doc.put("userId", video.getUserId());
        doc.put("title", video.getTitle());
        doc.put("description", video.getDescription());
        doc.put("authorName", user == null ? null : user.getUsername());
        doc.put("coverBucket", video.getCoverBucket());
        doc.put("coverObject", video.getFirstFrameObject());
        doc.put("viewCount", nullSafe(video.getViewCount()));
        doc.put("likeCount", nullSafe(video.getLikeCount()));
        doc.put("commentCount", nullSafe(video.getCommentCount()));
        doc.put("durationMs", video.getDurationMs());
        doc.put("publishTime", video.getPublishTime() == null ? null
                : video.getPublishTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        doc.put("status", video.getStatus());
        doc.put("deleted", video.getDeleted());
        return doc;
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }

    private static String normalize(String keyword) {
        if (keyword == null) {
            return "";
        }
        String trimmed = keyword.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }
}
