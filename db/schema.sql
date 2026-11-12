-- ============================================================
-- 视频点播（video_test_one）数据库脚本
-- MySQL 8.0+ / InnoDB / utf8mb4，仅需本机 MySQL
-- ============================================================

CREATE DATABASE IF NOT EXISTS `video_test_one`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `video_test_one`;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS `t_user`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`      VARCHAR(64)     NOT NULL COMMENT '用户名',
    `avatar_object` VARCHAR(255)             DEFAULT NULL COMMENT '头像 objectName',
    `avatar_url`    VARCHAR(512)             DEFAULT NULL COMMENT '头像外链（可选缓存）',
    `bili_mid`      VARCHAR(32)              DEFAULT NULL COMMENT 'B站 mid，供爬虫幂等导入',
    `follower_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '粉丝数',
    `like_count`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '获赞数',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_bili_mid` (`bili_mid`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- 2. 视频主表
CREATE TABLE IF NOT EXISTS `t_video`
(
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '视频ID',
    `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '投稿用户ID',
    `title`              VARCHAR(128)    NOT NULL DEFAULT '' COMMENT '视频标题',
    `description`        VARCHAR(2000)            DEFAULT NULL COMMENT '视频简介',
    `source_bucket`      VARCHAR(64)     NOT NULL DEFAULT 'video-source' COMMENT '源文件桶',
    `source_object`      VARCHAR(512)    NOT NULL COMMENT '源文件 objectName',
    `cover_bucket`       VARCHAR(64)     NOT NULL DEFAULT 'video-cover' COMMENT '封面桶',
    `first_frame_object` VARCHAR(512)             DEFAULT NULL COMMENT '首帧 objectName',
    `hls_bucket`         VARCHAR(64)     NOT NULL DEFAULT 'video-hls' COMMENT '转码桶',
    `m3u8_object`        VARCHAR(512)             DEFAULT NULL COMMENT 'm3u8 objectName',
    `duration_ms`        INT UNSIGNED              DEFAULT NULL COMMENT '时长（毫秒）',
    `view_count`         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '播放量',
    `comment_count`      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数（含回复，导入时刷新）',
    `like_count`         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数',
    `file_hash`          CHAR(32)        NOT NULL COMMENT '源文件 MD5',
    `file_size`          BIGINT UNSIGNED NOT NULL COMMENT '源文件字节数',
    `status`             TINYINT         NOT NULL DEFAULT 0 COMMENT '0草稿 1待转码 2转码中 3就绪 4失败',
    `error_msg`          VARCHAR(500)             DEFAULT NULL COMMENT '失败原因',
    `publish_time`       DATETIME(3)              DEFAULT NULL COMMENT '发布时间',
    `deleted`            TINYINT         NOT NULL DEFAULT 0 COMMENT '0正常 1删除',
    `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                         ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_hash` (`user_id`, `file_hash`) COMMENT '同一用户 MD5 秒传去重',
    KEY `idx_status_publish` (`status`, `publish_time`, `id`) COMMENT '推荐流查询',
    KEY `idx_user_status` (`user_id`, `status`, `id`) COMMENT '个人投稿列表'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='视频主表';

-- 3. 上传会话表
CREATE TABLE IF NOT EXISTS `t_video_upload_session`
(
    `upload_id`    VARCHAR(128)    NOT NULL COMMENT 'MinIO uploadId',
    `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `video_id`     BIGINT UNSIGNED NOT NULL COMMENT '草稿视频ID',
    `bucket`       VARCHAR(64)     NOT NULL DEFAULT 'video-source' COMMENT '目标桶',
    `object_name`  VARCHAR(512)    NOT NULL COMMENT '合并后 objectName',
    `file_name`    VARCHAR(255)    NOT NULL COMMENT '原始文件名',
    `file_size`    BIGINT UNSIGNED NOT NULL COMMENT '文件字节数',
    `file_hash`    CHAR(32)        NOT NULL COMMENT '文件 MD5',
    `chunk_size`   INT UNSIGNED    NOT NULL COMMENT '分片大小（字节）',
    `total_chunks` INT UNSIGNED    NOT NULL COMMENT '总分片数',
    `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0上传中 1已完成 2已取消 3已过期',
    `expire_at`    DATETIME(3)     NOT NULL COMMENT '会话过期时间',
    `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`upload_id`),
    KEY `idx_user_status` (`user_id`, `status`, `created_at`),
    KEY `idx_expire` (`status`, `expire_at`) COMMENT '过期清理扫描'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='视频分片上传会话';

-- 4. 转码任务表
CREATE TABLE IF NOT EXISTS `t_video_transcode_task`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `video_id`      BIGINT UNSIGNED NOT NULL COMMENT '视频ID',
    `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `source_bucket` VARCHAR(64)     NOT NULL DEFAULT 'video-source' COMMENT '源桶',
    `source_object` VARCHAR(512)    NOT NULL COMMENT '源文件 objectName',
    `target_bucket` VARCHAR(64)     NOT NULL DEFAULT 'video-hls' COMMENT '目标桶',
    `target_prefix` VARCHAR(512)    NOT NULL COMMENT '目标前缀 hls/{videoId}/',
    `mq_msg_id`     VARCHAR(64)              DEFAULT NULL COMMENT 'RocketMQ 消息ID',
    `status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待消费 1转码中 2成功 3失败',
    `retry_count`   TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '重试次数',
    `error_msg`     VARCHAR(500)             DEFAULT NULL COMMENT '失败原因',
    `started_at`    DATETIME(3)              DEFAULT NULL COMMENT '开始时间',
    `finished_at`   DATETIME(3)              DEFAULT NULL COMMENT '结束时间',
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_video` (`video_id`) COMMENT '一个视频一个任务，重试复用',
    KEY `idx_status_created` (`status`, `created_at`) COMMENT '补偿扫描'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='视频转码任务';

-- 5. 评论表
CREATE TABLE IF NOT EXISTS `t_comment`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `video_id`    BIGINT UNSIGNED NOT NULL COMMENT '视频ID',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '评论用户ID',
    `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=一级评论；否则为回复的评论ID',
    `root_id`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '一级评论ID；一级评论自身为0',
    `content`     VARCHAR(1000)   NOT NULL COMMENT '评论内容',
    `image_bucket` VARCHAR(64)              DEFAULT NULL COMMENT '评论图片桶',
    `image_object` VARCHAR(512)             DEFAULT NULL COMMENT '评论图片 objectName',
    `like_count`  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数',
    `reply_count` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '回复数（仅一级评论维护）',
    `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '0正常 1已删除',
    `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_video_level_time` (`video_id`, `parent_id`, `created_at` DESC, `id` DESC),
    KEY `idx_root_time` (`root_id`, `created_at` ASC, `id` ASC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='视频评论与回复';

-- 演示用户（前端默认自动登录「本地用户」）
INSERT INTO `t_user` (`username`)
VALUES ('本地用户')
ON DUPLICATE KEY UPDATE `username` = VALUES(`username`);

-- 6. 检索词库（热词 / 同义词，供搜索联想与热词接口使用）
CREATE TABLE IF NOT EXISTS `t_search_keyword`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '词条ID',
    `word`       VARCHAR(64)     NOT NULL COMMENT '检索词',
    `synonyms`   VARCHAR(255)             DEFAULT NULL COMMENT '同义词，英文逗号分隔',
    `weight`     INT             NOT NULL DEFAULT 0 COMMENT '权重，越大越靠前',
    `is_hot`     TINYINT         NOT NULL DEFAULT 0 COMMENT '1=热词',
    `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                  ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_word` (`word`),
    KEY `idx_hot_weight` (`is_hot`, `weight`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='检索词库（热词/同义词）';

INSERT INTO `t_search_keyword` (`word`, `synonyms`, `weight`, `is_hot`)
VALUES ('游戏', '电竞,网游,手游,单机', 100, 1),
       ('动漫', '动画,二次元,番剧', 96, 1),
       ('音乐', '歌曲,翻唱,原创音乐,MV', 92, 1),
       ('知识', '科普,学习,公开课,干货', 90, 1),
       ('科技', '数码,手机,电脑,AI', 88, 1),
       ('美食', '做饭,探店,吃播,料理', 84, 1),
       ('旅行', '旅游,vlog,风景,攻略', 80, 1),
       ('纪录片', '纪实,人文,自然', 76, 1),
       ('影视', '电影,剧集,解说,剪辑', 74, 1),
       ('鬼畜', '搞笑,整活,二创', 70, 1),
       ('生活', '日常,记录,分享', 66, 1),
       ('舞蹈', '宅舞,街舞,翻跳', 60, 0),
       ('健身', '运动,减脂,瑜伽', 56, 0),
       ('宠物', '猫,狗,萌宠', 52, 0),
       ('手工', 'DIY,制作,教程', 48, 0)
ON DUPLICATE KEY UPDATE `synonyms` = VALUES(`synonyms`),
                        `weight` = VALUES(`weight`),
                        `is_hot` = VALUES(`is_hot`);

