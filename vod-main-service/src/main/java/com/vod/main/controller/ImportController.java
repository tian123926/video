package com.vod.main.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vod.common.api.ApiResult;
import com.vod.common.api.ErrorCode;
import com.vod.common.constant.VideoConstants;
import com.vod.common.constant.VideoStatus;
import com.vod.common.exception.BusinessException;
import com.vod.main.dto.CommentImportRequest;
import com.vod.main.dto.ImportRequest;
import com.vod.main.dto.StatsImportRequest;
import com.vod.main.entity.Comment;
import com.vod.main.entity.User;
import com.vod.main.entity.Video;
import com.vod.main.mapper.CommentMapper;
import com.vod.main.mapper.UserMapper;
import com.vod.main.mapper.VideoMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 爬虫导入接口：脚本把作者 / 视频 / 评论一次性写入。
 */
@RestController
@RequestMapping("/api/video/internal")
public class ImportController {

    private final UserMapper userMapper;
    private final VideoMapper videoMapper;
    private final CommentMapper commentMapper;
    private final String serviceToken;

    public ImportController(UserMapper userMapper,
                            VideoMapper videoMapper,
                            CommentMapper commentMapper,
                            @Value("${vod.service-token:vod-internal-token}") String serviceToken) {
        this.userMapper = userMapper;
        this.videoMapper = videoMapper;
        this.commentMapper = commentMapper;
        this.serviceToken = serviceToken;
    }

    @PostMapping("/import")
    public ApiResult<Map<String, Object>> importVideo(
            @RequestHeader(value = VideoConstants.HEADER_SERVICE_TOKEN, required = false) String token,
            @RequestBody ImportRequest request) {
        if (token == null || !token.equals(serviceToken)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (request == null || request.getAuthor() == null || request.getVideo() == null
                || isBlank(request.getVideo().getVideoKey())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "author/video 不能为空");
        }

        Long authorId = upsertUser(request.getAuthor());
        Video video = upsertVideo(authorId, request.getVideo());
        int comments = replaceComments(video.getId(), request.getComments());
        refreshCommentCount(video, comments);

        Map<String, Object> result = new HashMap<>();
        result.put("videoId", video.getId());
        result.put("authorId", authorId);
        result.put("comments", comments);
        return ApiResult.ok(result);
    }

    @PostMapping("/comments")
    public ApiResult<Map<String, Object>> importComments(
            @RequestHeader(value = VideoConstants.HEADER_SERVICE_TOKEN, required = false) String token,
            @RequestBody CommentImportRequest request) {
        if (token == null || !token.equals(serviceToken)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (request == null || isBlank(request.getVideoKey())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "videoKey 不能为空");
        }
        Video video = videoMapper.selectOne(Wrappers.<Video>lambdaQuery()
                .eq(Video::getSourceObject, "bilibili/" + request.getVideoKey().trim())
                .last("LIMIT 1"));
        if (video == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "视频不存在");
        }
        int inserted = replaceComments(video.getId(), request.getComments());
        refreshCommentCount(video, inserted);
        Map<String, Object> result = new HashMap<>();
        result.put("videoId", video.getId());
        result.put("comments", inserted);
        return ApiResult.ok(result);
    }

    /**
     * 只回填播放量 / 点赞数，用于已入库视频的统计补齐。
     */
    @PostMapping("/stats")
    public ApiResult<Map<String, Object>> importStats(
            @RequestHeader(value = VideoConstants.HEADER_SERVICE_TOKEN, required = false) String token,
            @RequestBody StatsImportRequest request) {
        if (token == null || !token.equals(serviceToken)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "items 不能为空");
        }
        int updated = 0;
        for (StatsImportRequest.Item item : request.getItems()) {
            if (item == null || isBlank(item.getVideoKey())) {
                continue;
            }
            Video video = videoMapper.selectOne(Wrappers.<Video>lambdaQuery()
                    .eq(Video::getSourceObject, "bilibili/" + item.getVideoKey().trim())
                    .last("LIMIT 1"));
            if (video == null) {
                continue;
            }
            if (item.getViewCount() != null) {
                video.setViewCount(max0(item.getViewCount()));
            }
            if (item.getLikeCount() != null) {
                video.setLikeCount(max0(item.getLikeCount()));
            }
            videoMapper.updateById(video);
            updated++;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("updated", updated);
        return ApiResult.ok(result);
    }

    private Long upsertUser(ImportRequest.Author payload) {
        String externalId = trim(payload.getExternalId());
        User user = null;
        if (!externalId.isEmpty()) {
            user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                    .eq(User::getBiliMid, externalId)
                    .last("LIMIT 1"));
        }
        if (user == null) {
            user = new User();
            user.setUsername(trimToLength(trim(payload.getUsername()).isEmpty()
                    ? "UP主" : payload.getUsername(), 64));
            user.setBiliMid(externalId.isEmpty() ? null : externalId);
            user.setAvatarObject(trim(payload.getAvatarObject()));
            user.setFollowerCount(payload.getFansCount() == null ? 0L : max0(payload.getFansCount()));
            user.setLikeCount(payload.getLikeCount() == null ? 0L : max0(payload.getLikeCount()));
            insertUserSafely(user, externalId);
            return user.getId();
        }
        user.setUsername(trimToLength(payload.getUsername(), 64));
        if (!isBlank(payload.getAvatarObject())) {
            user.setAvatarObject(payload.getAvatarObject().trim());
        }
        if (payload.getFansCount() != null) {
            user.setFollowerCount(max0(payload.getFansCount()));
        }
        if (payload.getLikeCount() != null) {
            user.setLikeCount(max0(payload.getLikeCount()));
        }
        userMapper.updateById(user);
        return user.getId();
    }

    private Video upsertVideo(Long authorId, ImportRequest.VideoPayload payload) {
        String videoKey = payload.getVideoKey().trim();
        String sourceObject = "bilibili/" + videoKey;
        Video video = videoMapper.selectOne(Wrappers.<Video>lambdaQuery()
                .eq(Video::getSourceObject, sourceObject)
                .last("LIMIT 1"));
        boolean created = video == null;
        if (created) {
            video = new Video();
            video.setUserId(authorId);
            video.setSourceBucket(VideoConstants.BUCKET_SOURCE);
            video.setSourceObject(sourceObject);
            video.setFileHash(DigestUtils.md5DigestAsHex(sourceObject.getBytes(StandardCharsets.UTF_8)));
            video.setDeleted(0);
        }
        video.setUserId(authorId);
        video.setTitle(trimToLength(isBlank(payload.getTitle()) ? videoKey : payload.getTitle(), 128));
        video.setDescription(isBlank(payload.getDescription())
                ? null : trimToLength(payload.getDescription(), 2000));
        video.setCoverBucket(VideoConstants.BUCKET_COVER);
        video.setFirstFrameObject(trim(payload.getCoverObject()));
        video.setHlsBucket(VideoConstants.BUCKET_HLS);
        video.setM3u8Object(isBlank(payload.getManifestObject()) ? null : trim(payload.getManifestObject()));
        video.setDurationMs(payload.getDurationMs() == null
                ? 0 : Math.max(0, payload.getDurationMs().intValue()));
        video.setSegmentCount(payload.getSegmentCount() == null
                ? 0 : Math.max(0, payload.getSegmentCount()));
        video.setSegmentDurationMs(payload.getSegmentDurationMs() == null
                ? 0 : Math.max(0, payload.getSegmentDurationMs()));
        video.setAudioSegmentCount(payload.getAudioSegmentCount() == null
                ? 0 : Math.max(0, payload.getAudioSegmentCount()));
        video.setAudioSegmentDurationMs(payload.getAudioSegmentDurationMs() == null
                ? 0 : Math.max(0, payload.getAudioSegmentDurationMs()));
        video.setVideoCodecs(isBlank(payload.getVideoCodecs()) ? null : trim(payload.getVideoCodecs()));
        video.setAudioCodecs(isBlank(payload.getAudioCodecs()) ? null : trim(payload.getAudioCodecs()));
        video.setWidth(payload.getWidth() == null ? null : Math.max(0, payload.getWidth()));
        video.setHeight(payload.getHeight() == null ? null : Math.max(0, payload.getHeight()));
        video.setFileSize(payload.getFileSize() == null ? 0L : Math.max(0, payload.getFileSize()));
        if (payload.getViewCount() != null) {
            video.setViewCount(max0(payload.getViewCount()));
        }
        if (payload.getLikeCount() != null) {
            video.setLikeCount(max0(payload.getLikeCount()));
        }
        video.setStatus(VideoStatus.READY);
        video.setPublishTime(parseTime(payload.getPublishTime()));
        if (created) {
            videoMapper.insert(video);
        } else {
            videoMapper.updateById(video);
        }
        return video;
    }

    private void refreshCommentCount(Video video, int commentCount) {
        video.setCommentCount((long) Math.max(0, commentCount));
        videoMapper.updateById(video);
    }

    private int replaceComments(Long videoId, List<ImportRequest.CommentPayload> roots) {
        commentMapper.delete(Wrappers.<Comment>lambdaQuery().eq(Comment::getVideoId, videoId));
        if (roots == null || roots.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (ImportRequest.CommentPayload root : roots) {
            if (isBlank(root.getContent())) {
                continue;
            }
            Long rootUserId = upsertCommentUser(root);
            int replyCount = root.getReplies() == null ? 0 : root.getReplies().size();
            Comment entity = buildComment(videoId, rootUserId, 0L, 0L, root, 0, replyCount);
            commentMapper.insert(entity);
            inserted++;
            Long rootId = entity.getId();
            if (root.getReplies() != null) {
                for (ImportRequest.CommentPayload reply : root.getReplies()) {
                    if (isBlank(reply.getContent())) {
                        continue;
                    }
                    Long replyUserId = upsertCommentUser(reply);
                    Comment replyEntity = buildComment(videoId, replyUserId, entity.getId(), rootId,
                            reply, 1, 0);
                    commentMapper.insert(replyEntity);
                    inserted++;
                }
            }
        }
        return inserted;
    }

    private Long upsertCommentUser(ImportRequest.CommentPayload payload) {
        String externalId = trim(payload.getUserExternalId());
        User user = null;
        if (!externalId.isEmpty()) {
            user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                    .eq(User::getBiliMid, externalId)
                    .last("LIMIT 1"));
        }
        if (user == null) {
            user = new User();
            user.setUsername(trimToLength(isBlank(payload.getUsername()) ? "B站用户" : payload.getUsername(), 64));
            user.setBiliMid(externalId.isEmpty() ? null : externalId);
            user.setAvatarObject(trim(payload.getAvatarObject()));
            user.setFollowerCount(0L);
            user.setLikeCount(0L);
            insertUserSafely(user, externalId);
            return user.getId();
        }
        if (!isBlank(payload.getAvatarObject()) && isBlank(user.getAvatarObject())) {
            user.setAvatarObject(payload.getAvatarObject().trim());
            userMapper.updateById(user);
        }
        return user.getId();
    }

    private Comment buildComment(Long videoId, Long userId, Long parentId, Long rootId,
                                 ImportRequest.CommentPayload payload, int level, int replyCount) {
        Comment comment = new Comment();
        comment.setVideoId(videoId);
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setRootId(rootId);
        comment.setContent(trimToLength(payload.getContent(), 1000));
        comment.setImageBucket(isBlank(payload.getImageObject()) ? null : VideoConstants.BUCKET_COMMENT);
        comment.setImageObject(trim(payload.getImageObject()));
        comment.setLikeCount((int) max0(payload.getLikeCount()));
        comment.setReplyCount(replyCount);
        comment.setStatus(0);
        LocalDateTime createdAt = parseTime(payload.getCreatedAt());
        comment.setCreatedAt(createdAt);
        comment.setUpdatedAt(createdAt);
        return comment;
    }

    private static LocalDateTime parseTime(String value) {
        if (isBlank(value)) {
            return LocalDateTime.now();
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (Exception ignored) {
            // 非带时区格式，继续尝试本地时间
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }

    private void insertUserSafely(User user, String externalId) {
        try {
            userMapper.insert(user);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // B 站存在重名用户，追加 mid 后缀避免唯一键冲突
            String suffix = externalId == null || externalId.isBlank()
                    ? String.valueOf(System.nanoTime() % 100000)
                    : externalId.substring(Math.max(0, externalId.length() - 6));
            user.setUsername(trimToLength(user.getUsername() + "_" + suffix, 64));
            userMapper.insert(user);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToLength(String value, int max) {
        String trimmed = trim(value);
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static long max0(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static long max0(Integer value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
