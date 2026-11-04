package com.vod.main.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vod.common.api.ApiResult;
import com.vod.common.api.ErrorCode;
import com.vod.common.constant.VideoConstants;
import com.vod.common.constant.VideoStatus;
import com.vod.common.exception.BusinessException;
import com.vod.common.util.PlayUrlSigner;
import com.vod.main.client.MinioInternalClient;
import com.vod.main.client.dto.ObjectUrlItem;
import com.vod.main.dto.CommentItem;
import com.vod.main.dto.CommentPageResponse;
import com.vod.main.dto.ObjectUrlResponse;
import com.vod.main.dto.PlayResponse;
import com.vod.main.dto.RecommendItem;
import com.vod.main.dto.RecommendResponse;
import com.vod.main.dto.ReplyItem;
import com.vod.main.dto.VideoDetailResponse;
import com.vod.main.entity.Comment;
import com.vod.main.entity.User;
import com.vod.main.entity.Video;
import com.vod.main.mapper.CommentMapper;
import com.vod.main.mapper.UserMapper;
import com.vod.main.mapper.VideoMapper;
import com.vod.main.util.TimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 * 推荐 / 详情 / 播放 / 评论。
 */
@Slf4j
@RestController
@RequestMapping("/api/video")
public class VideoController {

    private static final int FIRST_FRAME_EXPIRES_SECONDS = 600;
    private static final int AVATAR_EXPIRES_SECONDS = 600;
    private static final int HLS_PLAYLIST_EXPIRES_SECONDS = 1800;
    private static final int MAX_RECOMMEND_COUNT = 20;
    private static final int MAX_COMMENT_PAGE_SIZE = 50;
    private static final int MAX_CACHED_REPLIES = 3;

    private static final Set<String> ALLOWED_BUCKETS = Set.of(
            VideoConstants.BUCKET_SOURCE,
            VideoConstants.BUCKET_COVER,
            VideoConstants.BUCKET_HLS,
            VideoConstants.BUCKET_AVATAR);

    private final VideoMapper videoMapper;
    private final UserMapper userMapper;
    private final CommentMapper commentMapper;
    private final MinioInternalClient minioInternalClient;
    private final PlayUrlSigner playUrlSigner;
    private final int playExpiresSeconds;
    private final String apiPublicBaseUrl;

    public VideoController(VideoMapper videoMapper,
                           UserMapper userMapper,
                           CommentMapper commentMapper,
                           MinioInternalClient minioInternalClient,
                           PlayUrlSigner playUrlSigner,
                           @Value("${vod.play.expires-seconds:1800}") int playExpiresSeconds,
                           @Value("${vod.api.public-base-url:http://127.0.0.1:8080}") String apiPublicBaseUrl) {
        this.videoMapper = videoMapper;
        this.userMapper = userMapper;
        this.commentMapper = commentMapper;
        this.minioInternalClient = minioInternalClient;
        this.playUrlSigner = playUrlSigner;
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

        Map<Long, User> userMap = loadUsers(rows.stream().map(Video::getUserId).toList());
        List<String> frameObjects = rows.stream()
                .map(Video::getFirstFrameObject)
                .filter(object -> !isBlank(object))
                .distinct()
                .toList();
        Map<String, ObjectUrlItem> frameSigns = signObjects(VideoConstants.BUCKET_COVER,
                frameObjects, FIRST_FRAME_EXPIRES_SECONDS);

        List<RecommendItem> items = new ArrayList<>(rows.size());
        for (Video video : rows) {
            User user = userMap.get(video.getUserId());
            ObjectUrlItem frameSign = frameSigns.get(video.getFirstFrameObject());
            items.add(new RecommendItem(
                    video.getId(),
                    video.getUserId(),
                    user == null ? null : user.getUsername(),
                    video.getTitle(),
                    TimeUtils.toOffset(video.getPublishTime()),
                    toSeconds(video.getDurationMs()),
                    video.getFirstFrameObject(),
                    frameSign == null ? null : frameSign.getUrl(),
                    frameSign == null ? null : frameSign.expiresAtOffset(FIRST_FRAME_EXPIRES_SECONDS)));
        }
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
                signSingle(VideoConstants.BUCKET_AVATAR,
                        user == null ? null : user.getAvatarObject(), AVATAR_EXPIRES_SECONDS),
                video.getTitle(),
                video.getDescription(),
                TimeUtils.toOffset(video.getPublishTime()),
                toSeconds(video.getDurationMs()),
                video.getStatus(),
                signSingle(VideoConstants.BUCKET_COVER,
                        video.getFirstFrameObject(), FIRST_FRAME_EXPIRES_SECONDS),
                commentCount));
    }

    @GetMapping("/{videoId}/play")
    public ApiResult<PlayResponse> play(@PathVariable Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null || isDeleted(video)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!Integer.valueOf(VideoStatus.READY).equals(video.getStatus()) || isBlank(video.getM3u8Object())) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_READY);
        }
        long expiresAtMillis = System.currentTimeMillis() + playExpiresSeconds * 1000L;
        String sign = playUrlSigner.sign(videoId, expiresAtMillis);
        String m3u8Url = apiPublicBaseUrl + "/api/video/" + videoId + "/playlist.m3u8?expires="
                + expiresAtMillis + "&sign=" + sign;
        return ApiResult.ok(new PlayResponse("hls", m3u8Url, playExpiresSeconds, true, 45, 10));
    }

    @GetMapping("/{videoId}/playlist.m3u8")
    public ResponseEntity<String> playlist(@PathVariable Long videoId,
                                           @RequestParam long expires,
                                           @RequestParam String sign) {
        if (!playUrlSigner.verify(videoId, expires, sign)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Video video = videoMapper.selectById(videoId);
        if (video == null || isDeleted(video)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!Integer.valueOf(VideoStatus.READY).equals(video.getStatus()) || isBlank(video.getM3u8Object())) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_READY);
        }
        String playlist = minioInternalClient.hlsPlaylist(video.getHlsBucket(), video.getM3u8Object(),
                HLS_PLAYLIST_EXPIRES_SECONDS);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .cacheControl(CacheControl.noStore())
                .body(playlist);
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
        for (Comment root : roots) {
            userIds.add(root.getUserId());
        }
        for (Long rootId : rootIds) {
            for (Comment reply : replyGroups.getOrDefault(rootId, List.of())) {
                userIds.add(reply.getUserId());
            }
        }
        Map<Long, User> userMap = loadUsers(userIds);
        Map<Long, String> avatarUrls = loadAvatarUrls(userMap.values());

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
                        TimeUtils.toOffset(reply.getCreatedAt())));
            }
            items.add(new CommentItem(
                    root.getId(),
                    root.getUserId(),
                    user == null ? null : user.getUsername(),
                    avatarUrls.get(root.getUserId()),
                    root.getContent(),
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

        List<CommentItem> items = new ArrayList<>(rows.size());
        for (Comment row : rows) {
            User user = userMap.get(row.getUserId());
            items.add(new CommentItem(
                    row.getId(),
                    row.getUserId(),
                    user == null ? null : user.getUsername(),
                    avatarUrls.get(row.getUserId()),
                    row.getContent(),
                    row.getLikeCount(),
                    row.getReplyCount(),
                    TimeUtils.toOffset(row.getCreatedAt()),
                    List.of()));
        }
        return new CommentPageResponse(total, page, size, items);
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
            avatarUrls.put(user.getId(), sign == null ? null : sign.getUrl());
        }
        return avatarUrls;
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
}
