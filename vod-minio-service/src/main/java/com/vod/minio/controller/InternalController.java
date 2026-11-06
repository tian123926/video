package com.vod.minio.controller;

import com.vod.common.api.ApiResult;
import com.vod.common.api.ErrorCode;
import com.vod.common.constant.VideoConstants;
import com.vod.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vod.common.constant.VideoStatus;
import com.vod.minio.dto.ObjectUrlsReq;
import com.vod.minio.entity.Video;
import com.vod.minio.mapper.VideoMapper;
import com.vod.minio.storage.MinioStorage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.DigestUtils;
import com.vod.minio.vo.ObjectUrlItem;
import com.vod.minio.vo.ObjectUrlResp;
import io.minio.GetObjectResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/internal")
public class InternalController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_EXPIRES = 604800;
    private static final int DEFAULT_OBJECT_EXPIRES = 604800;
    private static final int DEFAULT_PLAYLIST_EXPIRES = 1800;
    private static final Set<String> ALLOWED_BUCKETS = Set.of(
            VideoConstants.BUCKET_SOURCE,
            VideoConstants.BUCKET_COVER,
            VideoConstants.BUCKET_HLS,
            VideoConstants.BUCKET_AVATAR);

    private final MinioStorage minioStorage;
    private final VideoMapper videoMapper;

    public InternalController(MinioStorage minioStorage, VideoMapper videoMapper) {
        this.minioStorage = minioStorage;
        this.videoMapper = videoMapper;
    }

    @GetMapping("/object-url")
    public ApiResult<ObjectUrlResp> objectUrl(@RequestParam String bucket,
                                              @RequestParam String objectName,
                                              @RequestParam(required = false) Integer expires) {
        String targetBucket = validateBucket(bucket);
        if (isBlank(objectName)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "objectName 不能为空");
        }
        int expiry = normalizeExpires(expires, DEFAULT_OBJECT_EXPIRES);
        String url = minioStorage.presignGet(targetBucket, objectName, expiry);
        return ApiResult.ok(ObjectUrlResp.builder()
                .url(url)
                .expiresAt(OffsetDateTime.now(ZONE).plusSeconds(expiry))
                .build());
    }

    @PostMapping("/object-urls")
    public ApiResult<List<ObjectUrlItem>> objectUrls(@RequestBody ObjectUrlsReq req) {
        if (req == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请求体不能为空");
        }
        String bucket = validateBucket(req.getBucket());
        List<String> objectNames = req.getObjectNames();
        if (objectNames == null || objectNames.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "objectNames 不能为空");
        }
        int expiry = normalizeExpires(req.getExpires(), DEFAULT_OBJECT_EXPIRES);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZONE).plusSeconds(expiry);
        List<ObjectUrlItem> items = new ArrayList<>(objectNames.size());
        for (String objectName : objectNames) {
            if (isBlank(objectName)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "objectName 不能为空");
            }
            items.add(ObjectUrlItem.builder()
                    .objectName(objectName)
                    .url(minioStorage.presignGet(bucket, objectName, expiry))
                    .expiresAt(expiresAt)
                    .build());
        }
        return ApiResult.ok(items);
    }

    @GetMapping(value = "/hls-playlist", produces = "application/vnd.apple.mpegurl")
    public ResponseEntity<String> hlsPlaylist(@RequestParam String bucket,
                                              @RequestParam String objectName,
                                              @RequestParam(required = false) Integer expires) {
        String targetBucket = validateBucket(bucket);
        if (isBlank(objectName)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "objectName 不能为空");
        }
        int expiry = normalizeExpires(expires, DEFAULT_PLAYLIST_EXPIRES);
        String playlist = minioStorage.getObjectString(targetBucket, objectName);
        String directory = directoryOf(objectName);
        String rewritten = rewritePlaylist(playlist, targetBucket, directory, expiry);
        MediaType mediaType = new MediaType("application", "vnd.apple.mpegurl", StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(mediaType).body(rewritten);
    }

    /**
     * 内部流式下载：供集群内转码服务读取源文件，避免依赖对外可达的预签名地址。
     */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(@RequestParam String bucket,
                                                          @RequestParam String objectName) {
        String targetBucket = validateBucket(bucket);
        if (isBlank(objectName)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "objectName 不能为空");
        }
        GetObjectResponse object = minioStorage.getObject(targetBucket, objectName);
        StreamingResponseBody body = outputStream -> {
            try (object) {
                object.transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
                .body(body);
    }

    @PostMapping("/upload")
    public ApiResult<Map<String, String>> upload(@RequestParam String bucket,
                                                 @RequestParam String objectName,
                                                 @RequestPart("file") MultipartFile file) {
        String targetBucket = validateBucket(bucket);
        if (isBlank(objectName)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "objectName 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "file 不能为空");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }
        minioStorage.createBucketIfNotExists(targetBucket);
        minioStorage.putObject(targetBucket, objectName, content, file.getContentType());
        return ApiResult.ok(Map.of("bucket", targetBucket, "objectName", objectName));
    }

    /**
     * 登记一个已经写入 MinIO 的视频（供下载类脚本调用），按 videoKey 幂等。
     */
    @PostMapping("/video/register")
    public ApiResult<Map<String, Object>> registerVideo(@RequestParam String videoKey,
                                                        @RequestParam(required = false) String title,
                                                        @RequestParam(required = false) String description,
                                                        @RequestParam(required = false) String coverObject,
                                                        @RequestParam(required = false) String hlsObject,
                                                        @RequestParam(required = false) Long durationMs,
                                                        @RequestParam(required = false) Long fileSize) {
        if (isBlank(videoKey)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "videoKey 不能为空");
        }
        String sourceObject = "bilibili/" + videoKey.trim();
        Video existing = findBySourceObject(sourceObject);
        if (existing != null) {
            return ApiResult.ok(resultOf(existing.getId(), false));
        }

        Video video = new Video();
        video.setUserId(1L);
        video.setTitle(trimToLength(isBlank(title) ? videoKey : title, 128));
        video.setDescription(isBlank(description) ? null : trimToLength(description, 2000));
        video.setSourceBucket(VideoConstants.BUCKET_SOURCE);
        video.setSourceObject(sourceObject);
        video.setCoverBucket(VideoConstants.BUCKET_COVER);
        video.setFirstFrameObject(isBlank(coverObject) ? null : coverObject);
        video.setHlsBucket(VideoConstants.BUCKET_HLS);
        video.setM3u8Object(isBlank(hlsObject)
                ? "hls/" + videoKey.trim() + "/" + VideoConstants.HLS_PLAYLIST_NAME
                : hlsObject);
        video.setDurationMs(durationMs == null || durationMs < 0 ? 0 : durationMs.intValue());
        video.setFileHash(DigestUtils.md5DigestAsHex(sourceObject.getBytes(StandardCharsets.UTF_8)));
        video.setFileSize(fileSize == null || fileSize < 0 ? 0L : fileSize);
        video.setStatus(VideoStatus.READY);
        video.setPublishTime(LocalDateTime.now());
        video.setDeleted(0);

        try {
            videoMapper.insert(video);
        } catch (DuplicateKeyException ex) {
            Video duplicated = findBySourceObject(sourceObject);
            if (duplicated != null) {
                return ApiResult.ok(resultOf(duplicated.getId(), false));
            }
            throw ex;
        }
        return ApiResult.ok(resultOf(video.getId(), true));
    }

    private Video findBySourceObject(String sourceObject) {
        return videoMapper.selectOne(Wrappers.<Video>lambdaQuery()
                .eq(Video::getSourceObject, sourceObject)
                .last("LIMIT 1"));
    }

    private Map<String, Object> resultOf(Long videoId, boolean created) {
        Map<String, Object> result = new HashMap<>();
        result.put("videoId", videoId);
        result.put("created", created);
        return result;
    }

    private String trimToLength(String value, int maxLength) {
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private String rewritePlaylist(String playlist, String bucket, String directory, int expiry) {
        String[] lines = playlist.split("\n", -1);
        List<String> result = new ArrayList<>(lines.length);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")
                    || line.startsWith("http://") || line.startsWith("https://")) {
                result.add(raw);
                continue;
            }
            String relative = line.startsWith("/") ? line.substring(1) : line;
            result.add(minioStorage.presignGet(bucket, directory + relative, expiry));
        }
        return String.join("\n", result);
    }

    private String directoryOf(String objectName) {
        int index = objectName.lastIndexOf('/');
        return index >= 0 ? objectName.substring(0, index + 1) : "";
    }

    private String validateBucket(String bucket) {
        if (bucket == null || !ALLOWED_BUCKETS.contains(bucket)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的桶名称");
        }
        return bucket;
    }

    private int normalizeExpires(Integer expires, int defaultExpires) {
        if (expires == null || expires <= 0) {
            return defaultExpires;
        }
        return Math.min(expires, MAX_EXPIRES);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
