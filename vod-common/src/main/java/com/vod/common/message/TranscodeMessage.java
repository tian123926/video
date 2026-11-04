package com.vod.common.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 转码任务消息，由 minio 服务投递、转码服务消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscodeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long videoId;
}
