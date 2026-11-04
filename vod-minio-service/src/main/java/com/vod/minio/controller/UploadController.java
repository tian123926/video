package com.vod.minio.controller;

import com.vod.common.api.ApiResult;
import com.vod.common.auth.UserContext;
import com.vod.minio.dto.AbortReq;
import com.vod.minio.dto.CompleteReq;
import com.vod.minio.dto.PartUrlReq;
import com.vod.minio.dto.UploadInitReq;
import com.vod.minio.service.UploadService;
import com.vod.minio.vo.CompleteResp;
import com.vod.minio.vo.ListPartsResp;
import com.vod.minio.vo.PartUrlResp;
import com.vod.minio.vo.UploadInitResp;
import com.vod.minio.vo.UploadStatusResp;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/video/upload")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/init")
    public ApiResult<UploadInitResp> init(@RequestBody UploadInitReq req) {
        Long userId = UserContext.requireUserId();
        return ApiResult.ok(uploadService.init(userId, req));
    }

    @GetMapping("/list-parts")
    public ApiResult<ListPartsResp> listParts(@RequestParam String uploadId,
                                              @RequestParam(required = false) Integer partNumber) {
        Long userId = UserContext.requireUserId();
        return ApiResult.ok(uploadService.listParts(userId, uploadId, partNumber));
    }

    @PostMapping("/part-url")
    public ApiResult<PartUrlResp> partUrls(@RequestBody PartUrlReq req) {
        Long userId = UserContext.requireUserId();
        return ApiResult.ok(uploadService.partUrls(userId, req));
    }

    @PostMapping("/complete")
    public ApiResult<CompleteResp> complete(@RequestBody CompleteReq req) {
        Long userId = UserContext.requireUserId();
        return ApiResult.ok(uploadService.complete(userId, req));
    }

    @PostMapping("/abort")
    public ApiResult<Map<String, Object>> abort(@RequestBody AbortReq req) {
        Long userId = UserContext.requireUserId();
        return ApiResult.ok(uploadService.abort(userId, req));
    }

    @GetMapping("/status")
    public ApiResult<UploadStatusResp> status(@RequestParam Long videoId) {
        UserContext.requireUserId();
        return ApiResult.ok(uploadService.status(videoId));
    }
}
