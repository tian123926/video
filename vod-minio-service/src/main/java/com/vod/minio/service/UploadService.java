package com.vod.minio.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vod.common.api.ErrorCode;
import com.vod.common.constant.VideoConstants;
import com.vod.common.constant.VideoStatus;
import com.vod.common.exception.BusinessException;
import com.vod.common.message.TranscodeMessage;
import com.vod.minio.dto.AbortReq;
import com.vod.minio.dto.CompleteReq;
import com.vod.minio.dto.PartItem;
import com.vod.minio.dto.PartUrlReq;
import com.vod.minio.dto.UploadInitReq;
import com.vod.minio.entity.TranscodeTask;
import com.vod.minio.entity.UploadSession;
import com.vod.minio.entity.Video;
import com.vod.minio.mapper.TranscodeTaskMapper;
import com.vod.minio.mapper.UploadSessionMapper;
import com.vod.minio.mapper.VideoMapper;
import com.vod.minio.storage.MinioStorage;
import com.vod.minio.storage.PartInfo;
import com.vod.minio.vo.CompleteResp;
import com.vod.minio.vo.ListPartsResp;
import com.vod.minio.vo.PartUrlItem;
import com.vod.minio.vo.PartUrlResp;
import com.vod.minio.vo.UploadInitResp;
import com.vod.minio.vo.UploadStatusResp;
import com.vod.minio.vo.UploadedPart;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class UploadService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_PRESIGN_EXPIRES = 604800;
    private static final int SESSION_STATUS_EXPIRED = 3;

    private final VideoMapper videoMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final TranscodeTaskMapper transcodeTaskMapper;
    private final MinioStorage minioStorage;
    private final RocketMQTemplate rocketMQTemplate;
    private final long chunkSize;
    private final int partUrlBatch;

    public UploadService(VideoMapper videoMapper,
                         UploadSessionMapper uploadSessionMapper,
                         TranscodeTaskMapper transcodeTaskMapper,
                         MinioStorage minioStorage,
                         RocketMQTemplate rocketMQTemplate,
                         @Value("${vod.upload.chunk-size:5242880}") long chunkSize,
                         @Value("${vod.upload.part-url-batch:20}") int partUrlBatch) {
        this.videoMapper = videoMapper;
        this.uploadSessionMapper = uploadSessionMapper;
        this.transcodeTaskMapper = transcodeTaskMapper;
        this.minioStorage = minioStorage;
        this.rocketMQTemplate = rocketMQTemplate;
        this.chunkSize = chunkSize > 0 ? chunkSize : VideoConstants.DEFAULT_CHUNK_SIZE;
        this.partUrlBatch = partUrlBatch > 0 ? Math.min(partUrlBatch, VideoConstants.MAX_PART_URLS) : VideoConstants.MAX_PART_URLS;
    }

    public UploadInitResp init(Long userId, UploadInitReq req) {
        if (req == null || isBlank(req.getFileName()) || req.getFileSize() == null
                || req.getFileSize() <= 0 || isBlank(req.getFileHash())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "fileName/fileSize/fileHash 不能为空");
        }
        String fileName = req.getFileName().trim();
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_TYPE);
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!VideoConstants.isAllowedExtension(ext)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_TYPE);
        }
        long fileSize = req.getFileSize();
        if (fileSize > VideoConstants.MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        int totalChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);
        String fileHash = req.getFileHash().trim().toLowerCase(Locale.ROOT);
        String title = truncate(fileName.substring(0, dot).trim(), 128);
        if (title.isEmpty()) {
            title = truncate(fileName, 128);
        }

        Video video = videoMapper.selectOne(Wrappers.<Video>lambdaQuery()
                .eq(Video::getUserId, userId)
                .eq(Video::getFileHash, fileHash));

        String objectName;
        if (video != null) {
            boolean normal = video.getDeleted() == null || video.getDeleted() == 0;
            int status = video.getStatus() == null ? VideoStatus.INIT : video.getStatus();
            if (normal && (status == VideoStatus.UPLOADED || status == VideoStatus.TRANSCODING
                    || status == VideoStatus.READY)) {
                return UploadInitResp.builder()
                        .uploadId(null)
                        .videoId(video.getId())
                        .bucket(VideoConstants.BUCKET_SOURCE)
                        .objectName(video.getSourceObject())
                        .chunkSize((int) chunkSize)
                        .totalChunks(totalChunks)
                        .expireAt(null)
                        .existed(true)
                        .partUrls(List.of())
                        .build();
            }
            objectName = buildObjectName(userId, video.getId(), fileHash, ext);
            videoMapper.update(null, Wrappers.<Video>lambdaUpdate()
                    .eq(Video::getId, video.getId())
                    .set(Video::getTitle, title)
                    .set(Video::getSourceBucket, VideoConstants.BUCKET_SOURCE)
                    .set(Video::getSourceObject, objectName)
                    .set(Video::getFileSize, fileSize)
                    .set(Video::getStatus, VideoStatus.INIT)
                    .set(Video::getDeleted, 0)
                    .set(Video::getFirstFrameObject, null)
                    .set(Video::getM3u8Object, null)
                    .set(Video::getErrorMsg, null)
                    .set(Video::getPublishTime, null));
        } else {
            video = new Video();
            video.setUserId(userId);
            video.setTitle(title);
            video.setSourceBucket(VideoConstants.BUCKET_SOURCE);
            video.setSourceObject("");
            video.setCoverBucket(VideoConstants.BUCKET_COVER);
            video.setHlsBucket(VideoConstants.BUCKET_HLS);
            video.setFileHash(fileHash);
            video.setFileSize(fileSize);
            video.setStatus(VideoStatus.INIT);
            video.setDeleted(0);
            videoMapper.insert(video);

            objectName = buildObjectName(userId, video.getId(), fileHash, ext);
            videoMapper.update(null, Wrappers.<Video>lambdaUpdate()
                    .eq(Video::getId, video.getId())
                    .set(Video::getSourceObject, objectName));
        }

        minioStorage.createBucketIfNotExists(VideoConstants.BUCKET_SOURCE);
        String uploadId = minioStorage.initiateMultipartUpload(
                VideoConstants.BUCKET_SOURCE, objectName, req.getContentType());
        LocalDateTime expireAt = LocalDateTime.now(ZONE).plusMinutes(VideoConstants.SESSION_EXPIRE_MINUTES);

        UploadSession session = new UploadSession();
        session.setUploadId(uploadId);
        session.setUserId(userId);
        session.setVideoId(video.getId());
        session.setBucket(VideoConstants.BUCKET_SOURCE);
        session.setObjectName(objectName);
        session.setFileName(fileName);
        session.setFileSize(fileSize);
        session.setFileHash(fileHash);
        session.setChunkSize((int) chunkSize);
        session.setTotalChunks(totalChunks);
        session.setStatus(VideoStatus.SESSION_UPLOADING);
        session.setExpireAt(expireAt);
        uploadSessionMapper.insert(session);

        List<PartUrlItem> partUrls = presignParts(uploadId, objectName, 1, Math.min(totalChunks, partUrlBatch));

        return UploadInitResp.builder()
                .uploadId(uploadId)
                .videoId(video.getId())
                .bucket(VideoConstants.BUCKET_SOURCE)
                .objectName(objectName)
                .chunkSize((int) chunkSize)
                .totalChunks(totalChunks)
                .expireAt(toOffset(expireAt))
                .existed(false)
                .partUrls(partUrls)
                .build();
    }

    public PartUrlResp partUrls(Long userId, PartUrlReq req) {
        if (req == null || isBlank(req.getUploadId())
                || req.getPartNumbers() == null || req.getPartNumbers().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "uploadId/partNumbers 不能为空");
        }
        UploadSession session = uploadSessionMapper.selectById(req.getUploadId());
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "上传会话不存在");
        }
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "无权操作该上传会话");
        }
        if (session.getStatus() == null || session.getStatus() != VideoStatus.SESSION_UPLOADING
                || isExpired(session)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传会话已失效");
        }
        int totalChunks = session.getTotalChunks() == null ? 0 : session.getTotalChunks();
        List<Integer> partNumbers = req.getPartNumbers();
        if (partNumbers.size() > partUrlBatch) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "单次最多获取 " + partUrlBatch + " 个分片地址");
        }
        Set<Integer> unique = new LinkedHashSet<>();
        for (Integer partNumber : partNumbers) {
            if (partNumber == null || partNumber < 1 || partNumber > totalChunks) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "partNumber 超出范围");
            }
            unique.add(partNumber);
        }

        List<PartUrlItem> parts = new ArrayList<>(unique.size());
        int expiry = presignExpiry();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZONE).plusSeconds(expiry);
        for (Integer partNumber : unique) {
            parts.add(PartUrlItem.builder()
                    .partNumber(partNumber)
                    .url(minioStorage.presignPutPart(session.getBucket(), session.getObjectName(),
                            session.getUploadId(), partNumber, expiry))
                    .expiresAt(expiresAt)
                    .build());
        }
        return PartUrlResp.builder().parts(parts).build();
    }

    public ListPartsResp listParts(Long userId, String uploadId, Integer partNumber) {
        if (isBlank(uploadId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "uploadId 不能为空");
        }
        UploadSession session = uploadSessionMapper.selectById(uploadId);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "上传会话不存在");
        }
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (session.getStatus() != null && session.getStatus() == VideoStatus.SESSION_ABORTED) {
            throw new BusinessException(ErrorCode.SESSION_INVALID);
        }

        List<UploadedPart> uploaded = loadUploadedParts(session);
        int totalChunks = session.getTotalChunks() == null ? 0 : session.getTotalChunks();

        if (partNumber != null) {
            UploadedPart matched = uploaded.stream()
                    .filter(part -> part.getPartNumber() == partNumber)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该分片尚未上传"));
            return ListPartsResp.builder()
                    .uploadId(uploadId)
                    .totalChunks(totalChunks)
                    .uploadedParts(List.of(matched))
                    .missingParts(List.of())
                    .build();
        }

        Set<Integer> uploadedNumbers = new LinkedHashSet<>();
        uploaded.forEach(part -> uploadedNumbers.add(part.getPartNumber()));
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= totalChunks; i++) {
            if (!uploadedNumbers.contains(i)) {
                missing.add(i);
            }
        }
        return ListPartsResp.builder()
                .uploadId(uploadId)
                .totalChunks(totalChunks)
                .uploadedParts(uploaded)
                .missingParts(missing)
                .build();
    }

    public CompleteResp complete(Long userId, CompleteReq req) {
        if (req == null || isBlank(req.getUploadId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "uploadId 不能为空");
        }
        UploadSession session = uploadSessionMapper.selectById(req.getUploadId());
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "上传会话不存在");
        }
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        if (session.getStatus() != null && session.getStatus() == VideoStatus.SESSION_COMPLETED) {
            TranscodeTask task = upsertTranscodeTask(session);
            sendTranscodeMessage(session, task);
            return completeResp(session, task);
        }
        if (session.getStatus() == null || session.getStatus() != VideoStatus.SESSION_UPLOADING
                || isExpired(session)) {
            throw new BusinessException(ErrorCode.SESSION_INVALID);
        }

        int totalChunks = session.getTotalChunks() == null ? 0 : session.getTotalChunks();
        List<PartItem> submittedParts = req.getParts();
        if (submittedParts == null || submittedParts.size() != totalChunks) {
            throw new BusinessException(ErrorCode.PARTS_MISSING);
        }
        Map<Integer, String> submitted = new HashMap<>();
        for (PartItem item : submittedParts) {
            if (item == null || item.getPartNumber() == null || isBlank(item.getEtag())
                    || item.getPartNumber() < 1 || item.getPartNumber() > totalChunks
                    || submitted.put(item.getPartNumber(), item.getEtag()) != null) {
                throw new BusinessException(ErrorCode.ETAG_MISMATCH);
            }
        }
        if (submitted.size() != totalChunks) {
            throw new BusinessException(ErrorCode.PARTS_MISSING);
        }

        Map<Integer, PartInfo> actual = new HashMap<>();
        for (PartInfo part : minioStorage.listParts(session.getBucket(), session.getObjectName(), session.getUploadId())) {
            actual.put(part.getPartNumber(), part);
        }
        List<PartInfo> ordered = new ArrayList<>(totalChunks);
        for (int i = 1; i <= totalChunks; i++) {
            PartInfo part = actual.get(i);
            if (part == null) {
                throw new BusinessException(ErrorCode.PARTS_MISSING);
            }
            if (!normalizeEtag(submitted.get(i)).equals(normalizeEtag(part.getEtag()))) {
                throw new BusinessException(ErrorCode.ETAG_MISMATCH);
            }
            ordered.add(part);
        }
        ordered.sort(Comparator.comparingInt(PartInfo::getPartNumber));

        minioStorage.completeMultipart(session.getBucket(), session.getObjectName(),
                session.getUploadId(), ordered);

        uploadSessionMapper.update(null, Wrappers.<UploadSession>lambdaUpdate()
                .eq(UploadSession::getUploadId, session.getUploadId())
                .set(UploadSession::getStatus, VideoStatus.SESSION_COMPLETED));
        videoMapper.update(null, Wrappers.<Video>lambdaUpdate()
                .eq(Video::getId, session.getVideoId())
                .set(Video::getStatus, VideoStatus.UPLOADED)
                .set(Video::getDeleted, 0)
                .set(Video::getErrorMsg, null));

        TranscodeTask task = upsertTranscodeTask(session);
        sendTranscodeMessage(session, task);

        session.setStatus(VideoStatus.SESSION_COMPLETED);
        return completeResp(session, task);
    }

    public Map<String, Object> abort(Long userId, AbortReq req) {
        if (req == null || isBlank(req.getUploadId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "uploadId 不能为空");
        }
        UploadSession session = uploadSessionMapper.selectById(req.getUploadId());
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "上传会话不存在");
        }
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (session.getStatus() != null && session.getStatus() == VideoStatus.SESSION_ABORTED) {
            return Map.of("aborted", true);
        }
        if (session.getStatus() == null || session.getStatus() != VideoStatus.SESSION_UPLOADING) {
            throw new BusinessException(ErrorCode.SESSION_INVALID);
        }

        minioStorage.abortMultipart(session.getBucket(), session.getObjectName(), session.getUploadId());
        uploadSessionMapper.update(null, Wrappers.<UploadSession>lambdaUpdate()
                .eq(UploadSession::getUploadId, session.getUploadId())
                .set(UploadSession::getStatus, VideoStatus.SESSION_ABORTED));
        videoMapper.update(null, Wrappers.<Video>lambdaUpdate()
                .eq(Video::getId, session.getVideoId())
                .set(Video::getDeleted, 1));
        return Map.of("aborted", true);
    }

    public UploadStatusResp status(Long videoId) {
        if (videoId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "videoId 不能为空");
        }
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "视频不存在");
        }
        UploadSession session = uploadSessionMapper.selectOne(Wrappers.<UploadSession>lambdaQuery()
                .eq(UploadSession::getVideoId, videoId)
                .orderByDesc(UploadSession::getCreatedAt)
                .last("limit 1"));
        TranscodeTask task = transcodeTaskMapper.selectOne(Wrappers.<TranscodeTask>lambdaQuery()
                .eq(TranscodeTask::getVideoId, videoId));

        String errorMsg = video.getErrorMsg();
        if (isBlank(errorMsg) && task != null) {
            errorMsg = task.getErrorMsg();
        }
        Integer uploadStatus = session == null ? null : session.getStatus();
        if (uploadStatus != null && uploadStatus == VideoStatus.SESSION_UPLOADING && isExpired(session)) {
            uploadStatus = SESSION_STATUS_EXPIRED;
        }
        return UploadStatusResp.builder()
                .videoId(videoId)
                .uploadStatus(uploadStatus)
                .videoStatus(video.getStatus())
                .progress(null)
                .playUrl(null)
                .errorMsg(errorMsg)
                .build();
    }

    private List<UploadedPart> loadUploadedParts(UploadSession session) {
        List<PartInfo> parts = minioStorage.listParts(session.getBucket(), session.getObjectName(), session.getUploadId());
        List<UploadedPart> uploaded = new ArrayList<>(parts.size());
        for (PartInfo part : parts) {
            uploaded.add(UploadedPart.builder()
                    .partNumber(part.getPartNumber())
                    .etag(stripQuotes(part.getEtag()))
                    .size(part.getSize())
                    .build());
        }
        return uploaded;
    }

    private List<PartUrlItem> presignParts(String uploadId, String objectName, int from, int to) {
        List<PartUrlItem> items = new ArrayList<>();
        if (to < from) {
            return items;
        }
        int expiry = presignExpiry();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZONE).plusSeconds(expiry);
        for (int partNumber = from; partNumber <= to; partNumber++) {
            items.add(PartUrlItem.builder()
                    .partNumber(partNumber)
                    .url(minioStorage.presignPutPart(VideoConstants.BUCKET_SOURCE, objectName,
                            uploadId, partNumber, expiry))
                    .expiresAt(expiresAt)
                    .build());
        }
        return items;
    }

    private TranscodeTask upsertTranscodeTask(UploadSession session) {
        TranscodeTask existing = transcodeTaskMapper.selectOne(Wrappers.<TranscodeTask>lambdaQuery()
                .eq(TranscodeTask::getVideoId, session.getVideoId()));
        if (existing == null) {
            TranscodeTask task = new TranscodeTask();
            task.setVideoId(session.getVideoId());
            task.setUserId(session.getUserId());
            task.setSourceBucket(session.getBucket());
            task.setSourceObject(session.getObjectName());
            task.setTargetBucket(VideoConstants.BUCKET_HLS);
            task.setTargetPrefix(hlsPrefix(session.getVideoId()));
            task.setStatus(VideoStatus.TASK_WAITING);
            task.setRetryCount(0);
            transcodeTaskMapper.insert(task);
            return task;
        }
        transcodeTaskMapper.update(null, Wrappers.<TranscodeTask>lambdaUpdate()
                .eq(TranscodeTask::getId, existing.getId())
                .set(TranscodeTask::getUserId, session.getUserId())
                .set(TranscodeTask::getSourceBucket, session.getBucket())
                .set(TranscodeTask::getSourceObject, session.getObjectName())
                .set(TranscodeTask::getTargetBucket, VideoConstants.BUCKET_HLS)
                .set(TranscodeTask::getTargetPrefix, hlsPrefix(session.getVideoId()))
                .set(TranscodeTask::getStatus, VideoStatus.TASK_WAITING)
                .set(TranscodeTask::getErrorMsg, null)
                .set(TranscodeTask::getStartedAt, null)
                .set(TranscodeTask::getFinishedAt, null)
                .set(TranscodeTask::getMqMsgId, null));
        return existing;
    }

    private void sendTranscodeMessage(UploadSession session, TranscodeTask task) {
        try {
            SendResult result = rocketMQTemplate.syncSend(VideoConstants.TOPIC_TRANSCODE,
                    TranscodeMessage.builder()
                            .userId(session.getUserId())
                            .videoId(session.getVideoId())
                            .build());
            transcodeTaskMapper.update(null, Wrappers.<TranscodeTask>lambdaUpdate()
                    .eq(TranscodeTask::getId, task.getId())
                    .set(TranscodeTask::getMqMsgId, result.getMsgId()));
        } catch (Exception ex) {
            log.error("投递转码消息失败, videoId={}", session.getVideoId(), ex);
            throw new BusinessException(ErrorCode.MQ_UNAVAILABLE);
        }
    }

    private CompleteResp completeResp(UploadSession session, TranscodeTask task) {
        return CompleteResp.builder()
                .videoId(session.getVideoId())
                .status(VideoStatus.UPLOADED)
                .transcodeTaskId(task.getId())
                .mergedObject(session.getObjectName())
                .build();
    }

    private boolean isExpired(UploadSession session) {
        return session.getExpireAt() != null && session.getExpireAt().isBefore(LocalDateTime.now(ZONE));
    }

    private int presignExpiry() {
        int configured = minioStorage.getPresignExpirySeconds();
        if (configured <= 0) {
            return VideoConstants.DEFAULT_PLAY_EXPIRE_SECONDS;
        }
        return Math.min(configured, MAX_PRESIGN_EXPIRES);
    }

    private String buildObjectName(Long userId, Long videoId, String fileHash, String ext) {
        return "source/" + userId + "/" + videoId + "/" + fileHash + "." + ext;
    }

    private String hlsPrefix(Long videoId) {
        return "hls/" + videoId + "/";
    }

    private String normalizeEtag(String etag) {
        return stripQuotes(etag).toLowerCase(Locale.ROOT);
    }

    private String stripQuotes(String etag) {
        if (etag == null) {
            return "";
        }
        String value = etag.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(ZONE).toOffsetDateTime();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
