-- ============================================================
-- 多巴胺平台 MySQL 建表脚本
-- 启动时由 Spring Boot 自动执行（spring.sql.init.mode=always）
-- 全部使用 IF NOT EXISTS，幂等可重复执行，后续启动不修改已存在的表结构
-- Hibernate ddl-auto=none，禁止 Hibernate 干预表结构
-- ============================================================

-- 流程表条目：今日/明日分组，状态流转，可上交
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
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 对话记录，区分办公/多巴胺模式
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `mode`       ENUM('DOPAMINE','WORK') NOT NULL,
    `chat_role`  VARCHAR(20)  NOT NULL,
    `content`    MEDIUMTEXT   NOT NULL,
    `created_at` DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
