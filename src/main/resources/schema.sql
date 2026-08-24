-- ============================================================
-- 多巴胺平台 MySQL 建表脚本
-- 启动时由 Spring Boot 自动执行（spring.sql.init.mode=always）
-- 全部使用 IF NOT EXISTS，幂等可重复执行，后续启动不修改已存在的表结构
-- Hibernate ddl-auto=none，禁止 Hibernate 干预表结构
-- ============================================================

-- 流程表条目：今日/明日分组，状态流转，可上交；软删除用于工作留痕
CREATE TABLE IF NOT EXISTS `task` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `title`             VARCHAR(200) NOT NULL,
    `category`          ENUM('CUSTOM','MEAL','WORK')          NOT NULL,
    `task_group`        ENUM('TODAY','TOMORROW')              NOT NULL,
    `task_status`       ENUM('TODO','DOING','DONE','SUBMITTED') NOT NULL,
    `source`            ENUM('LLM','MANUAL')                  NOT NULL,
    `estimated_minutes` INT,
    `due_at`            DATETIME(6),
    `created_at`        DATETIME(6)   NOT NULL,
    `submitted_at`      DATETIME(6)   NULL COMMENT '上交时间：任务被标记为 SUBMITTED 时写入',
    `deleted`           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除标记：0=正常 1=已删除',
    `deleted_at`        DATETIME(6)   NULL COMMENT '软删除时间',
    PRIMARY KEY (`id`),
    KEY `idx_task_status_deleted` (`task_status`, `deleted`),
    KEY `idx_task_deleted_at` (`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 多套 API 配置持久化：多模态（文本/图片/语音/视频）与 天气服务共用一张表
-- 安全设计：删除 = 物理 DELETE 行，防止 API Key 残留在数据库
-- profile_type 用 VARCHAR(20) 而非 MySQL ENUM，方便将来扩展能力（如 EMBEDDING/SEARCH）不用改 DDL
CREATE TABLE IF NOT EXISTS `api_profile` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `profile_type`        VARCHAR(20)  NOT NULL COMMENT 'LLM=文本对话 IMAGE=文生图 AUDIO=TTS+ASR VIDEO=文生视频 WEATHER=天气',
    `name`                VARCHAR(100) NOT NULL,
    `provider`            VARCHAR(50)  NULL,
    `base_url`            VARCHAR(500) NULL,
    `model`               VARCHAR(100) NULL COMMENT 'LLM:对话模型ID  IMAGE:dall-e-3/seedream-t2i-xxl  AUDIO:tts-1/whisper-1  VIDEO:seedance-t2v-xxl',
    `api_key`             MEDIUMTEXT   NOT NULL,
    `location`            VARCHAR(100) NULL COMMENT '仅天气用',
    `cache_ttl_seconds`   BIGINT       NULL COMMENT '仅天气用',
    `timeout_seconds`     INT          NULL COMMENT '单次请求超时秒数，视频生成建议 120 以上',
    `is_active`           TINYINT(1)   NOT NULL DEFAULT 0,
    `created_at`          DATETIME(6)  NOT NULL,
    `updated_at`          DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_api_profile_type_active` (`profile_type`, `is_active`),
    KEY `idx_api_profile_type_updated` (`profile_type`, `updated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 对话记录，区分办公/多巴胺模式
-- 每种模式保留最近 MAX_HISTORY_PER_MODE（默认 50）条，超出自动删除最旧的
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `mode`       ENUM('DOPAMINE','WORK') NOT NULL,
    `chat_role`  VARCHAR(20)  NOT NULL,
    `content`    MEDIUMTEXT   NOT NULL,
    `created_at` DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_chat_mode_created` (`mode`, `created_at` DESC, `id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 系统配置表：key-value 结构，存放大模型提供商等可运行时修改的配置
CREATE TABLE IF NOT EXISTS `system_config` (
    `cfg_key`    VARCHAR(100)  NOT NULL,
    `cfg_value`  MEDIUMTEXT    NOT NULL,
    `updated_at` DATETIME(6)   NOT NULL,
    PRIMARY KEY (`cfg_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 注意：已有数据库的 task 表补齐 submitted_at/deleted/deleted_at 列和索引
-- 由 Java 启动迁移组件 SchemaMigrator 自动完成（见 config/SchemaMigrator.java），
-- 使用 JdbcTemplate 查询 information_schema 判断后再 ALTER，避免 DELIMITER 兼容性问题。
