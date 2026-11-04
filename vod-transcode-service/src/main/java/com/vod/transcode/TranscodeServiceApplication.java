package com.vod.transcode;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 转码微服务启动类：消费 RocketMQ 转码消息，调用 ffmpeg 生成 HLS 产物。
 */
@SpringBootApplication(scanBasePackages = "com.vod")
@MapperScan("com.vod.transcode.mapper")
public class TranscodeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranscodeServiceApplication.class, args);
    }
}
