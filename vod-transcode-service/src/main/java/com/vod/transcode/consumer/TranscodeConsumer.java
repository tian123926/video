package com.vod.transcode.consumer;

import com.vod.common.constant.VideoConstants;
import com.vod.common.message.TranscodeMessage;
import com.vod.transcode.service.TranscodeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 转码 MQ 消费者：异常仅记录日志，不抛出以避免无限重试。
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = VideoConstants.TOPIC_TRANSCODE, consumerGroup = VideoConstants.GROUP_TRANSCODE)
public class TranscodeConsumer implements RocketMQListener<TranscodeMessage> {

    private final TranscodeService transcodeService;

    public TranscodeConsumer(TranscodeService transcodeService) {
        this.transcodeService = transcodeService;
    }

    @Override
    public void onMessage(TranscodeMessage message) {
        log.info("收到转码消息: {}", message);
        try {
            transcodeService.process(message);
        } catch (Exception e) {
            log.error("消费转码消息失败, 不重试: {}", message, e);
        }
    }
}
