package com.vod.main.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vod.common.api.ApiResult;
import com.vod.common.api.ErrorCode;
import com.vod.common.auth.UserContext;
import com.vod.common.constant.VideoConstants;
import com.vod.common.constant.VideoStatus;
import com.vod.common.exception.BusinessException;
import com.vod.common.util.PlayUrlSigner;
import com.vod.main.client.MinioInternalClient;
import com.vod.main.client.dto.ObjectUrlItem;
import com.vod.main.dto.CommentCreateRequest;
import com.vod.main.dto.CommentItem;
import com.vod.main.dto.CommentPageResponse;
import com.vod.main.dto.HotKeyword;
import com.vod.main.dto.ObjectUrlResponse;
import com.vod.main.dto.PlayResponse;
import com.vod.main.dto.RecommendItem;
import com.vod.main.dto.RecommendResponse;
import com.vod.main.dto.ReplyItem;
import com.vod.main.dto.SearchResponse;
import com.vod.main.dto.UserResponse;
import com.vod.main.dto.UserSearchItem;
import com.vod.main.dto.VideoDetailResponse;
import com.vod.main.entity.Comment;
import com.vod.main.entity.User;
import com.vod.main.entity.Video;
import com.vod.main.mapper.CommentMapper;
import com.vod.main.mapper.UserMapper;
import com.vod.main.mapper.VideoMapper;
import com.vod.main.search.VideoSearchService;
import com.vod.main.util.TimeUtils;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 推荐 / 详情 / 播放 / 评论 / 个人主页。
 */
@Slf4j
@RestController
@RequestMapping("/api/video")
public class VideoController {

    private static final int FIRST_FRAME_EXPIRES_SECONDS = 86400;
    private static final int AVATAR_EXPIRES_SECONDS = 86400;
    private static final int HLS_PLAYLIST_EXPIRES_SECONDS = 1800;
    private static final int MAX_RECOMMEND_COUNT = 20;
    private static final int MAX_COMMENT_PAGE_SIZE = 50;
    private static final int MAX_CACHED_REPLIES = 3;

    private static final Set<String> ALLOWED_BUCKETS = Set.of(
            VideoConstants.BUCKET_SOURCE,
            VideoConstants.BUCKET_COVER,
            VideoConstants.BUCKET_HLS,
            VideoConstants.BUCKET_AVATAR,
            VideoConstants.BUCKET_COMMENT);

    private final VideoMapper videoMapper;
    private final UserMapper userMapper;
    private final CommentMapper commentMapper;
    private final MinioInternalClient minioInternalClient;
    private final PlayUrlSigner playUrlSigner;
    private final VideoSearchService videoSearchService;
    private final int playExpiresSeconds;
    private final String apiPublicBaseUrl;

    public VideoController(VideoMapper videoMapper,
                           UserMapper userMapper,
                           CommentMapper commentMapper,
                           MinioInternalClient minioInternalClient,
                           PlayUrlSigner playUrlSigner,
                           VideoSearchService videoSearchService,
                           @Value("${vod.play.expires-seconds:1800}") int playExpiresSeconds,
                           @Value("${vod.api.public-base-url:http://127.0.0.1:8080}") String apiPublicBaseUrl) {
        this.videoMapper = videoMapper;
        this.userMapper = userMapper;
        this.commentMapper = commentMapper;
        this.minioInternalClient = minioInternalClient;
        this.playUrlSigner = playUrlSigner;
        this.videoSearchService = videoSearchService;
        this.playExpiresSeconds = playExpiresSeconds;
        this.apiPublicBaseUrl = apiPublicBaseUrl.endsWith("/")
                ? apiPublicBaseUrl.substring(0, apiPublicBaseUrl.length() - 1)
                : apiPublicBaseUrl;
    }

    @GetMapping("/recommend")
    public ApiResult<RecommendResponse> recommend(
            @RequestParam(defaultValue = "8") int count,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "false") boolean refresh) {
        int limit = Math.min(Math.max(count, 1), MAX_RECOMMEND_COUNT);
        LambdaQueryWrapper<Video> wrapper = Wrappers.lambdaQuery(Video.class)
                .eq(Video::getStatus, VideoStatus.READY)
                .eq(Video::getDeleted, 0);
        if (cursor != null) {
            wrapper.lt(Video::getId, cursor);
        }
        wrapper.orderByDesc(Video::getId).last("LIMIT " + (limit + 1));
        List<Video> rows = videoMapper.selectList(wrapper);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        List<RecommendItem> items = buildItems(rows);
        String nextCursor = items.isEmpty() ? null
                : String.valueOf(items.get(items.size() - 1).getVideoId());
        return ApiResult.ok(new RecommendResponse(items, nextCursor, hasMore));
    }

    /**
     * 视频检索：ES 多字段（标题/简介/作者名）优先，ES 不可用时回退数据库。
     */
    @GetMapping("/search")
    public ApiResult<SearchResponse> search(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer count,
            @RequestParam(defaultValue = "score") String sort,
            @RequestParam(required = false) String cursor) {
        String kw = keyword == null ? "" : keyword.trim();
        int pageSize = count != null && count > 0 ? count : size;
        if (kw.isEmpty()) {
            return ApiResult.ok(new SearchResponse(kw, 0, 1, pageSize, false, List.of(), null, "empty"));
        }
        // 首页或携带游标时走混合检索（关键词/自然语言分流 + 三路召回融合）
        if (cursor != null || page <= 1) {
            VideoSearchService.CursorPage hybrid = videoSearchService.searchByCursor(kw, cursor, pageSize);
            return ApiResult.ok(new SearchResponse(
                    kw,
                    hybrid.total(),
                    1,
                    hybrid.size(),
                    hybrid.hasMore(),
                    buildItems(hybrid.rows()),
                    hybrid.cursor(),
                    hybrid.source()));
        }
        VideoSearchService.SearchPage result = videoSearchService.search(kw, page, pageSize, sort);
        return ApiResult.ok(new SearchResponse(
                kw,
                result.total(),
                result.page(),
                result.size(),
                result.hasMore(),
                buildItems(result.rows()),
                null,
                result.source()));
    }

    /** 检索联想：词库前缀 + ES 标题/作者 + 数据库标题兜底 */
    @GetMapping("/search/suggest")
    public ApiResult<Map<String, Object>> searchSuggest(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "8") int limit) {
        List<String> items = videoSearchService.suggest(keyword, limit);
        return ApiResult.ok(Map.of("items", items));
    }

    /** 检索热词 */
    @GetMapping("/search/hot")
    public ApiResult<Map<String, Object>> searchHot(
            @RequestParam(defaultValue = "10") int limit) {
        List<HotKeyword> items = videoSearchService.hot(limit);
        return ApiResult.ok(Map.of("items", items));
    }

    /** 检索「用户」页签 */
    @GetMapping("/search/users")
    public ApiResult<Map<String, Object>> searchUsers(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return ApiResult.ok(Map.of("items", List.of()));
        }
        int max = Math.min(Math.max(limit, 1), 20);
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .like(User::getUsername, kw)
                .orderByDesc(User::getFollowerCount)
                .last("LIMIT " + max));
        List<UserSearchItem> items = new ArrayList<>(users.size());
        for (User user : users) {
            long videoCount = videoMapper.selectCount(Wrappers.lambdaQuery(Video.class)
                    .eq(Video::getUserId, user.getId())
                    .eq(Video::getStatus, VideoStatus.READY)
                    .eq(Video::getDeleted, 0));
            items.add(new UserSearchItem(user.getId(), user.getUsername(), avatarFor(user),
                    nullSafe(user.getFollowerCount()), nullSafe(user.getLikeCount()), videoCount));
        }
        return ApiResult.ok(Map.of("items", items));
    }

    /** 手动触发全量重建索引（运维/联调用） */
    @PostMapping("/search/reindex")
    public ApiResult<Map<String, Object>> reindex() {
        int indexed = videoSearchService.syncAll();
        return ApiResult.ok(Map.of("indexed", indexed));
    }

    @GetMapping("/user/{userId}")
    public ApiResult<UserResponse> userProfile(@PathVariable Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return ApiResult.ok(new UserResponse(user.getId(), user.getUsername(),
                avatarFor(user),
                nullSafe(user.getFollowerCount()), nullSafe(user.getLikeCount())));
    }

    @GetMapping("/user/{userId}/videos")
    public ApiResult<RecommendResponse> userVideos(@PathVariable Long userId,
                                                   @RequestParam(defaultValue = "12") int count,
                                                   @RequestParam(required = false) Long cursor) {
        int limit = Math.min(Math.max(count, 1), MAX_RECOMMEND_COUNT);
        LambdaQueryWrapper<Video> wrapper = Wrappers.lambdaQuery(Video.class)
                .eq(Video::getUserId, userId)
                .eq(Video::getStatus, VideoStatus.READY)
                .eq(Video::getDeleted, 0);
        if (cursor != null) {
            wrapper.lt(Video::getId, cursor);
        }
        wrapper.orderByDesc(Video::getId).last("LIMIT " + (limit + 1));
        List<Video> rows = videoMapper.selectList(wrapper);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        List<RecommendItem> items = buildItems(rows);
        String nextCursor = items.isEmpty() ? null
                : String.valueOf(items.get(items.size() - 1).getVideoId());
        return ApiResult.ok(new RecommendResponse(items, nextCursor, hasMore));
    }

    @GetMapping("/object-url")
    public ApiResult<ObjectUrlResponse> objectUrl(
            @RequestParam String bucket,
            @RequestParam String objectName,
            @RequestParam(defaultValue = "600") int expires) {
        if (!ALLOWED_BUCKETS.contains(bucket)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "bucket 不合法");
        }
        if (isBlank(objectName)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "objectName 不能为空");
        }
        int ttl = Math.min(Math.max(expires, 1), 3600);
        var resp = minioInternalClient.objectUrl(bucket, objectName, ttl);
        return ApiResult.ok(new ObjectUrlResponse(resp.getUrl(), resp.expiresAtOffset(ttl)));
    }

    @GetMapping("/{videoId}/detail")
    public ApiResult<VideoDetailResponse> detail(@PathVariable Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null || isDeleted(video)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        User user = video.getUserId() == null ? null : userMapper.selectById(video.getUserId());
        long commentCount = commentMapper.selectCount(Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getVideoId, videoId)
                .eq(Comment::getStatus, 0));
        return ApiResult.ok(new VideoDetailResponse(
                video.getId(),
                video.getUserId(),
                user == null ? null : user.getUsername(),
                avatarFor(user),
                user == null ? 0L : nullSafe(user.getFollowerCount()),
                video.getTitle(),
                video.getDescription(),
                TimeUtils.toOffset(video.getPublishTime()),
                toSeconds(video.getDurationMs()),
                video.getStatus(),
                signSingle(VideoConstants.BUCKET_COVER,
                        video.getFirstFrameObject(), FIRST_FRAME_EXPIRES_SECONDS),
                commentCount,
                nullSafe(video.getViewCount()),
                nullSafe(video.getLikeCount()),
                user == null ? 0L : nullSafe(user.getLikeCount())));
    }

    @GetMapping("/{videoId}/play")
    public ApiResult<PlayResponse> play(@PathVariable Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null || isDeleted(video)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!Integer.valueOf(VideoStatus.READY).equals(video.getStatus())
                || (!isSegmented(video) && isBlank(video.getM3u8Object()))) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_READY);
        }
        // 分片模式使用动态生成索引；其余按清单后缀识别协议
        boolean dash = isSegmented(video)
                || (video.getM3u8Object() != null && video.getM3u8Object().toLowerCase().endsWith(".mpd"));
        long expiresAtMillis = System.currentTimeMillis() + playExpiresSeconds * 1000L;
        String sign = playUrlSigner.sign(videoId, expiresAtMillis);
        String manifestUrl = apiPublicBaseUrl + "/api/video/" + videoId
                + (dash ? "/manifest.mpd?expires=" : "/playlist.m3u8?expires=")
                + expiresAtMillis + "&sign=" + sign;
        return ApiResult.ok(new PlayResponse(dash ? "dash" : "hls", manifestUrl,
                playExpiresSeconds, true, 45, 10));
    }

    @GetMapping("/{videoId}/playlist.m3u8")
    public ResponseEntity<String> playlist(@PathVariable Long videoId,
                                           @RequestParam long expires,
                                           @RequestParam String sign) {
        Video video = requirePlayable(videoId, expires, sign);
        String playlist = minioInternalClient.hlsPlaylist(video.getHlsBucket(), video.getM3u8Object(),
                HLS_PLAYLIST_EXPIRES_SECONDS);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .cacheControl(CacheControl.noStore())
                .body(playlist);
    }

    @GetMapping(value = "/{videoId}/manifest.mpd")
    public ResponseEntity<String> manifest(@PathVariable Long videoId,
                                           @RequestParam long expires,
                                           @RequestParam String sign) {
        Video video = requirePlayable(videoId, expires, sign);
        String manifest = isSegmented(video)
                ? minioInternalClient.generatedDashManifest(
                        video.getHlsBucket(),
                        authorBiliMid(video),
                        videoKey(video),
                        video.getSegmentCount(),
                        video.getSegmentDurationMs(),
                        video.getAudioSegmentCount(),
                        video.getAudioSegmentDurationMs(),
                        video.getDurationMs(),
                        video.getVideoCodecs(),
                        video.getAudioCodecs(),
                        video.getWidth(),
                        video.getHeight(),
                        HLS_PLAYLIST_EXPIRES_SECONDS)
                : minioInternalClient.dashManifest(video.getHlsBucket(), video.getM3u8Object(),
                        HLS_PLAYLIST_EXPIRES_SECONDS);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/dash+xml"))
                .cacheControl(CacheControl.noStore())
                .body(manifest);
    }

    @GetMapping("/{videoId}/comments")
    public ApiResult<CommentPageResponse> comments(
            @PathVariable Long videoId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "0") long parentId) {
        if (page < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "page 必须大于 0");
        }
        int pageSize = Math.min(Math.max(size, 1), MAX_COMMENT_PAGE_SIZE);
        long offset = (long) (page - 1) * pageSize;
        CommentPageResponse response = parentId == 0
                ? loadTopLevelComments(videoId, page, pageSize, offset)
                : loadReplies(parentId, page, pageSize, offset);
        return ApiResult.ok(response);
    }

    @PostMapping("/{videoId}/comments")
    public ApiResult<Map<String, Object>> createComment(@PathVariable Long videoId,
                                                        @Valid @RequestBody CommentCreateRequest request) {
        Long userId = UserContext.requireUserId();
        Video video = videoMapper.selectById(videoId);
        if (video == null || isDeleted(video)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "评论内容不能为空");
        }
        long parentId = request.getParentId() == null ? 0L : request.getParentId();
        long rootId = 0L;
        Comment parent = null;
        if (parentId > 0) {
            parent = commentMapper.selectById(parentId);
            if (parent == null || !videoId.equals(parent.getVideoId())
                    || !Integer.valueOf(0).equals(parent.getStatus())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "回复的评论不存在");
            }
            rootId = parent.getParentId() != null && parent.getParentId() == 0 ? parent.getId() : parent.getRootId();
        }
        Comment comment = new Comment();
        comment.setVideoId(videoId);
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setRootId(rootId);
        comment.setContent(content.length() > 1000 ? content.substring(0, 1000) : content);
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setStatus(0);
        comment.setCreatedAt(LocalDateTime.now());
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.insert(comment);
        if (parent != null) {
            parent.setReplyCount((parent.getReplyCount() == null ? 0 : parent.getReplyCount()) + 1);
            parent.setUpdatedAt(LocalDateTime.now());
            commentMapper.updateById(parent);
        }
        video.setCommentCount(nullSafe(video.getCommentCount()) + 1);
        videoMapper.updateById(video);
        Map<String, Object> result = new HashMap<>();
        result.put("commentId", comment.getId());
        return ApiResult.ok(result);
    }

    private Video requirePlayable(Long videoId, long expires, String sign) {
        String normalized = sign == null ? "" : sign.trim();
        while (normalized.endsWith("?")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!playUrlSigner.verify(videoId, expires, normalized)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Video video = videoMapper.selectById(videoId);
        if (video == null || isDeleted(video)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!Integer.valueOf(VideoStatus.READY).equals(video.getStatus())
                || (!isSegmented(video) && isBlank(video.getM3u8Object()))) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_READY);
        }
        return video;
    }

    /** 是否为「分片 + 动态索引」模式 */
    private static boolean isSegmented(Video video) {
        return video.getSegmentCount() != null && video.getSegmentCount() > 0;
    }

    /** 从 source_object（bilibili/{bvid}）中取出 bvid */
    private static String videoKey(Video video) {
        String source = video.getSourceObject();
        if (isBlank(source)) {
            return "";
        }
        int index = source.lastIndexOf('/');
        return index >= 0 ? source.substring(index + 1) : source;
    }

    /** 作者 B 站 mid，作为 MinIO 存储路径的第一段（去掉 bili: 前缀） */
    private String authorBiliMid(Video video) {
        User user = userMapper.selectById(video.getUserId());
        if (user == null || isBlank(user.getBiliMid())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "视频作者缺少 B 站 UID");
        }
        String mid = user.getBiliMid().trim();
        return mid.startsWith("bili:") ? mid.substring("bili:".length()) : mid;
    }

    private List<RecommendItem> buildItems(List<Video> rows) {
        Map<Long, User> userMap = loadUsers(rows.stream().map(Video::getUserId).toList());
        List<String> frameObjects = rows.stream()
                .map(Video::getFirstFrameObject)
                .filter(object -> !isBlank(object))
                .distinct()
                .toList();
        Map<String, ObjectUrlItem> frameSigns = signObjects(VideoConstants.BUCKET_COVER,
                frameObjects, FIRST_FRAME_EXPIRES_SECONDS);
        Map<Long, String> avatarUrls = loadAvatarUrls(userMap.values());

        List<RecommendItem> items = new ArrayList<>(rows.size());
        for (Video video : rows) {
            User user = userMap.get(video.getUserId());
            ObjectUrlItem frameSign = frameSigns.get(video.getFirstFrameObject());
            items.add(new RecommendItem(
                    video.getId(),
                    video.getUserId(),
                    user == null ? null : user.getUsername(),
                    avatarUrls.get(video.getUserId()),
                    video.getTitle(),
                    TimeUtils.toOffset(video.getPublishTime()),
                    toSeconds(video.getDurationMs()),
                    video.getFirstFrameObject(),
                    frameSign == null ? null : frameSign.getUrl(),
                    frameSign == null ? null : frameSign.expiresAtOffset(FIRST_FRAME_EXPIRES_SECONDS),
                    nullSafe(video.getViewCount()),
                    nullSafe(video.getLikeCount()),
                    nullSafe(video.getCommentCount()),
                    user == null ? 0L : nullSafe(user.getFollowerCount()),
                    user == null ? 0L : nullSafe(user.getLikeCount())));
        }
        return items;
    }

    private CommentPageResponse loadTopLevelComments(Long videoId, int page, int size, long offset) {
        LambdaQueryWrapper<Comment> countWrapper = Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getVideoId, videoId)
                .eq(Comment::getParentId, 0)
                .eq(Comment::getStatus, 0);
        long total = commentMapper.selectCount(countWrapper);

        List<Comment> roots = commentMapper.selectList(Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getVideoId, videoId)
                .eq(Comment::getParentId, 0)
                .eq(Comment::getStatus, 0)
                .orderByDesc(Comment::getCreatedAt)
                .orderByDesc(Comment::getId)
                .last("LIMIT " + offset + "," + size));
        if (roots.isEmpty()) {
            return new CommentPageResponse(total, page, size, List.of());
        }

        List<Long> rootIds = roots.stream().map(Comment::getId).toList();
        Map<Long, List<Comment>> replyGroups = new HashMap<>();
        if (!rootIds.isEmpty()) {
            List<Comment> replies = commentMapper.selectList(Wrappers.lambdaQuery(Comment.class)
                    .in(Comment::getRootId, rootIds)
                    .ne(Comment::getParentId, 0)
                    .eq(Comment::getStatus, 0)
                    .orderByAsc(Comment::getCreatedAt)
                    .orderByAsc(Comment::getId));
            for (Comment reply : replies) {
                replyGroups.computeIfAbsent(reply.getRootId(), key -> new ArrayList<>()).add(reply);
            }
        }
        for (Long rootId : rootIds) {
            List<Comment> replies = replyGroups.get(rootId);
            if (replies != null && replies.size() > MAX_CACHED_REPLIES) {
                replyGroups.put(rootId, new ArrayList<>(replies.subList(0, MAX_CACHED_REPLIES)));
            }
        }

        Set<Long> userIds = new LinkedHashSet<>();
        List<Comment> allComments = new ArrayList<>(roots);
        for (Comment root : roots) {
            userIds.add(root.getUserId());
        }
        for (Long rootId : rootIds) {
            for (Comment reply : replyGroups.getOrDefault(rootId, List.of())) {
                userIds.add(reply.getUserId());
                allComments.add(reply);
            }
        }
        Map<Long, User> userMap = loadUsers(userIds);
        Map<Long, String> avatarUrls = loadAvatarUrls(userMap.values());
        Map<String, String> imageUrls = signCommentImages(allComments);

        List<CommentItem> items = new ArrayList<>(roots.size());
        for (Comment root : roots) {
            User user = userMap.get(root.getUserId());
            List<ReplyItem> replies = new ArrayList<>();
            for (Comment reply : replyGroups.getOrDefault(root.getId(), List.of())) {
                User replyUser = userMap.get(reply.getUserId());
                replies.add(new ReplyItem(
                        reply.getId(),
                        reply.getUserId(),
                        replyUser == null ? null : replyUser.getUsername(),
                        avatarUrls.get(reply.getUserId()),
                        reply.getContent(),
                        imageUrls.get(reply.getImageObject()),
                        TimeUtils.toOffset(reply.getCreatedAt())));
            }
            items.add(new CommentItem(
                    root.getId(),
                    root.getUserId(),
                    user == null ? null : user.getUsername(),
                    avatarUrls.get(root.getUserId()),
                    root.getContent(),
                    imageUrls.get(root.getImageObject()),
                    root.getLikeCount(),
                    root.getReplyCount(),
                    TimeUtils.toOffset(root.getCreatedAt()),
                    replies));
        }
        return new CommentPageResponse(total, page, size, items);
    }

    private CommentPageResponse loadReplies(long rootId, int page, int size, long offset) {
        long total = commentMapper.selectCount(Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getRootId, rootId)
                .ne(Comment::getParentId, 0)
                .eq(Comment::getStatus, 0));

        List<Comment> rows = commentMapper.selectList(Wrappers.lambdaQuery(Comment.class)
                .eq(Comment::getRootId, rootId)
                .ne(Comment::getParentId, 0)
                .eq(Comment::getStatus, 0)
                .orderByAsc(Comment::getCreatedAt)
                .orderByAsc(Comment::getId)
                .last("LIMIT " + offset + "," + size));
        Set<Long> userIds = new HashSet<>();
        for (Comment row : rows) {
            userIds.add(row.getUserId());
        }
        Map<Long, User> userMap = loadUsers(userIds);
        Map<Long, String> avatarUrls = loadAvatarUrls(userMap.values());
        Map<String, String> imageUrls = signCommentImages(rows);

        List<CommentItem> items = new ArrayList<>(rows.size());
        for (Comment row : rows) {
            User user = userMap.get(row.getUserId());
            items.add(new CommentItem(
                    row.getId(),
                    row.getUserId(),
                    user == null ? null : user.getUsername(),
                    avatarUrls.get(row.getUserId()),
                    row.getContent(),
                    imageUrls.get(row.getImageObject()),
                    row.getLikeCount(),
                    row.getReplyCount(),
                    TimeUtils.toOffset(row.getCreatedAt()),
                    List.of()));
        }
        return new CommentPageResponse(total, page, size, items);
    }

    private Map<String, String> signCommentImages(Collection<Comment> comments) {
        Map<String, String> result = new HashMap<>();
        List<String> objects = comments.stream()
                .map(Comment::getImageObject)
                .filter(object -> !isBlank(object))
                .distinct()
                .toList();
        if (objects.isEmpty()) {
            return result;
        }
        Map<String, ObjectUrlItem> signs = signObjects(VideoConstants.BUCKET_COMMENT, objects,
                FIRST_FRAME_EXPIRES_SECONDS);
        signs.forEach((object, item) -> result.put(object, item.getUrl()));
        return result;
    }

    private Map<Long, User> loadUsers(Collection<Long> userIds) {
        Map<Long, User> userMap = new HashMap<>();
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return userMap;
        }
        for (User user : userMapper.selectBatchIds(ids)) {
            userMap.put(user.getId(), user);
        }
        return userMap;
    }

    private Map<Long, String> loadAvatarUrls(Collection<User> users) {
        Map<Long, String> avatarUrls = new HashMap<>();
        List<String> objects = users.stream()
                .map(User::getAvatarObject)
                .filter(object -> !isBlank(object))
                .distinct()
                .toList();
        Map<String, ObjectUrlItem> signs = signObjects(VideoConstants.BUCKET_AVATAR,
                objects, AVATAR_EXPIRES_SECONDS);
        for (User user : users) {
            ObjectUrlItem sign = isBlank(user.getAvatarObject()) ? null : signs.get(user.getAvatarObject());
            avatarUrls.put(user.getId(), sign == null ? user.getAvatarUrl() : sign.getUrl());
        }
        return avatarUrls;
    }

    private String avatarFor(User user) {
        if (user == null) {
            return null;
        }
        String signed = signSingle(VideoConstants.BUCKET_AVATAR, user.getAvatarObject(), AVATAR_EXPIRES_SECONDS);
        return signed != null ? signed : user.getAvatarUrl();
    }

    private Map<String, ObjectUrlItem> signObjects(String bucket, List<String> objectNames, int expires) {
        Map<String, ObjectUrlItem> signs = new HashMap<>();
        if (objectNames.isEmpty()) {
            return signs;
        }
        for (ObjectUrlItem item : minioInternalClient.batchObjectUrls(bucket, objectNames, expires)) {
            if (item != null && item.getObjectName() != null && item.getUrl() != null) {
                signs.put(item.getObjectName(), item);
            }
        }
        return signs;
    }

    private String signSingle(String bucket, String objectName, int expires) {
        if (isBlank(objectName)) {
            return null;
        }
        try {
            return minioInternalClient.objectUrl(bucket, objectName, expires).getUrl();
        } catch (BusinessException ex) {
            log.warn("对象签名失败 bucket={} objectName={}: {}", bucket, objectName, ex.getMessage());
            return null;
        }
    }

    private static boolean isDeleted(Video video) {
        return Integer.valueOf(1).equals(video.getDeleted());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Long toSeconds(Integer durationMs) {
        return durationMs == null ? null : Math.round(durationMs / 1000.0);
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}
