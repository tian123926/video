package com.vod.main.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动后异步把 MySQL 中已就绪的视频同步到 ES。
 */
@Slf4j
@Component
public class SearchIndexBootstrap {

    private final VideoSearchService videoSearchService;

    @Value("${vod.search.enabled:true}")
    private boolean enabled;

    @Value("${vod.search.sync-on-boot:true}")
    private boolean syncOnBoot;

    public SearchIndexBootstrap(VideoSearchService videoSearchService) {
        this.videoSearchService = videoSearchService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled || !syncOnBoot) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                videoSearchService.syncAll();
            } catch (Exception ex) {
                log.warn("视频索引启动同步失败（不影响服务启动）: {}", ex.getMessage());
            }
        }, "es-index-bootstrap");
        thread.setDaemon(true);
        thread.start();
    }
}
