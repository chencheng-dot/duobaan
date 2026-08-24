package org.example.duobaan.config;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等 Schema 迁移：为已有旧数据库补齐缺失的列与索引。
 * schema.sql 中 CREATE TABLE IF NOT EXISTS 只在全新数据库生效。
 * 这里通过 information_schema 判断列/索引是否存在，不存在才 ALTER，
 * 避免 DELIMITER / 存储过程在 Spring ScriptUtils 下的兼容问题。
 */
@Component
public class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        try {
            String dbName = jdbc.queryForObject("SELECT DATABASE()", String.class);
            if (dbName == null || dbName.isBlank()) {
                log.warn("[SchemaMigrator] 当前数据库名为空，跳过迁移");
                return;
            }
            migrateTaskTable(dbName);
            migrateChatMessageTable(dbName);
            migrateApiProfileTable(dbName);
        } catch (Exception e) {
            // 升级失败不阻止启动（全新数据库列已在 schema.sql 建好，这里只是兼容旧库）
            log.warn("[SchemaMigrator] 迁移过程发生异常，跳过（全新库可忽略）：{}", e.getMessage());
        }
    }

    private void migrateTaskTable(String dbName) {
        // submitted_at
        if (!columnExists(dbName, "task", "submitted_at")) {
            jdbc.execute("ALTER TABLE `task` "
                    + "ADD COLUMN `submitted_at` DATETIME(6) NULL "
                    + "COMMENT '上交时间：任务被标记为 SUBMITTED 时写入' "
                    + "AFTER `created_at`");
            log.info("[SchemaMigrator] task.submitted_at 列已添加");
        }
        // deleted
        if (!columnExists(dbName, "task", "deleted")) {
            jdbc.execute("ALTER TABLE `task` "
                    + "ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0 "
                    + "COMMENT '软删除标记：0=正常 1=已删除' "
                    + "AFTER `submitted_at`");
            log.info("[SchemaMigrator] task.deleted 列已添加");
        }
        // deleted_at
        if (!columnExists(dbName, "task", "deleted_at")) {
            jdbc.execute("ALTER TABLE `task` "
                    + "ADD COLUMN `deleted_at` DATETIME(6) NULL "
                    + "COMMENT '软删除时间' "
                    + "AFTER `deleted`");
            log.info("[SchemaMigrator] task.deleted_at 列已添加");
        }
        // index: idx_task_status_deleted
        if (!indexExists(dbName, "task", "idx_task_status_deleted")) {
            jdbc.execute("ALTER TABLE `task` ADD KEY `idx_task_status_deleted` (`task_status`, `deleted`)");
            log.info("[SchemaMigrator] idx_task_status_deleted 索引已添加");
        }
        // index: idx_task_deleted_at
        if (!indexExists(dbName, "task", "idx_task_deleted_at")) {
            jdbc.execute("ALTER TABLE `task` ADD KEY `idx_task_deleted_at` (`deleted_at`)");
            log.info("[SchemaMigrator] idx_task_deleted_at 索引已添加");
        }
    }

    /** chat_message 表：补齐复合索引 idx_chat_mode_created，让按 mode + 时间倒序/正序走索引 */
    private void migrateChatMessageTable(String dbName) {
        if (!indexExists(dbName, "chat_message", "idx_chat_mode_created")) {
            jdbc.execute("ALTER TABLE `chat_message` "
                    + "ADD KEY `idx_chat_mode_created` (`mode`, `created_at` DESC, `id` DESC)");
            log.info("[SchemaMigrator] chat_message.idx_chat_mode_created 索引已添加");
        }
    }

    /**
     * api_profile 表：从旧版 ENUM('LLM','WEATHER') → VARCHAR(20)。
     * 判断依据：COLUMN_TYPE 里以 'enum(' 开头即视为旧版，需要 MODIFY。
     * 升级后再扩展 IMAGE/AUDIO/VIDEO 等新类型无需再改 DDL。
     */
    private void migrateApiProfileTable(String dbName) {
        String colType = null;
        try {
            colType = jdbc.queryForObject(
                    "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'api_profile' "
                            + "AND COLUMN_NAME = 'profile_type'",
                    String.class, dbName);
        } catch (Exception ignore) {
            return; // 表不存在（全新库会由 schema.sql 直接建 VARCHAR）
        }
        if (colType != null && colType.toLowerCase().startsWith("enum(")) {
            jdbc.execute("ALTER TABLE `api_profile` "
                    + "MODIFY COLUMN `profile_type` VARCHAR(20) NOT NULL "
                    + "COMMENT 'LLM=文本对话 IMAGE=文生图 AUDIO=TTS+ASR VIDEO=文生视频 WEATHER=天气'");
            log.info("[SchemaMigrator] api_profile.profile_type 已从 ENUM 升级为 VARCHAR(20)，支持多模态类型扩展");
        }
    }

    private boolean columnExists(String db, String table, String column) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT 1 FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ? "
                        + "LIMIT 1",
                db, table, column);
        return !rows.isEmpty();
    }

    private boolean indexExists(String db, String table, String indexName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT 1 FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND INDEX_NAME = ? "
                        + "LIMIT 1",
                db, table, indexName);
        return !rows.isEmpty();
    }
}
