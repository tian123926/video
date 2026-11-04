package com.vod.main;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 点播主服务：推荐 / 详情 / 播放 / 评论。
 */
@SpringBootApplication(scanBasePackages = "com.vod")
@MapperScan("com.vod.main.mapper")
public class MainServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MainServiceApplication.class, args);
    }
}
