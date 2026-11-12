package com.vod.main.search;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.alibaba.otter.canal.protocol.Message;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 订阅 Canal 的 t_video 行变更（binlog），实时刷新 ES 索引。
 */
@Slf4j
@Component
public class CanalVideoSync {

    private final VideoSearchService videoSearchService;
    private final AiSearchClient aiSearchClient;

    @Value("${vod.search.canal-enabled:false}")
    private boolean enabled;

    @Value("${vod.search.canal-server:127.0.0.1:11111}")
    private String server;

    @Value("${vod.search.canal-destination:example}")
    private String destination;

    @Value("${vod.search.canal-filter:video_test_one\\..*}")
    private String filter;

    private volatile boolean running = true;

    public CanalVideoSync(VideoSearchService videoSearchService, AiSearchClient aiSearchClient) {
        this.videoSearchService = videoSearchService;
        this.aiSearchClient = aiSearchClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("Canal 视频索引同步未启用");
            return;
        }
        Thread thread = new Thread(this::loop, "canal-video-sync");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    private void loop() {
        String[] parts = server.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 11111;
        while (running) {
            CanalConnector connector = CanalConnectors.newSingleConnector(
                    new InetSocketAddress(host, port), destination, "", "");
            try {
                connector.connect();
                connector.subscribe(filter);
                connector.rollback();
                log.info("Canal 已连接 {}，订阅 {}", server, filter);
                while (running) {
                    Message message = connector.getWithoutAck(100, 1L, TimeUnit.SECONDS);
                    long batchId = message.getId();
                    if (batchId == -1 || message.getEntries().isEmpty()) {
                        if (batchId != -1) {
                            connector.ack(batchId);
                        }
                        continue;
                    }
                    try {
                        handleEntries(message.getEntries());
                        connector.ack(batchId);
                    } catch (Exception ex) {
                        log.warn("Canal 批次处理失败，回滚后重试: {}", ex.getMessage());
                        connector.rollback(batchId);
                        sleep(1000);
                    }
                }
            } catch (Exception ex) {
                log.warn("Canal 连接异常，5 秒后重连: {}", ex.getMessage());
                sleep(5000);
            } finally {
                try {
                    connector.disconnect();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
        }
    }

    private void handleEntries(List<Entry> entries) throws Exception {
        for (Entry entry : entries) {
            if (entry.getEntryType() != EntryType.ROWDATA) {
                continue;
            }
            RowChange rowChange = RowChange.parseFrom(entry.getStoreValue());
            EventType eventType = rowChange.getEventType();
            for (RowData rowData : rowChange.getRowDatasList()) {
                List<Column> columns = eventType == EventType.DELETE
                        ? rowData.getBeforeColumnsList()
                        : rowData.getAfterColumnsList();
                for (Column column : columns) {
                    if ("id".equalsIgnoreCase(column.getName())) {
                        try {
                            long videoId = Long.parseLong(column.getValue());
                            videoSearchService.refreshVideo(videoId);
                            // binlog 变更后触发 AI 管线：抽帧 + 字幕 + 向量化（可播放视频才处理）
                            if (eventType != EventType.DELETE) {
                                aiSearchClient.submitProcess(videoId);
                            }
                        } catch (NumberFormatException ignored) {
                            // 非法的行 ID 跳过
                        }
                        break;
                    }
                }
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
