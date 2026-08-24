package org.example.duobaan.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.example.duobaan.model.ChatMessage;
import org.example.duobaan.model.ChatMode;
import org.example.duobaan.repository.ChatMessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话历史服务：
 * - 按 mode（办公 / 多巴胺）隔离存储
 * - 每条消息追加后自动 trim：每 mode 仅保留最近 50 条，超出物理删除最旧的
 * - 刷新页面时拉历史即可恢复上次对话
 *
 * 说明：用户在需求里提到可以用 Redis 缓存，但考虑：
 *   1) 现有项目只依赖 MySQL，额外引入 Redis 会增加部署成本（本地/服务器都要起）；
 *   2) 限长 50 条 × 2 mode，一共最多 100 行，MySQL + 复合索引（mode, created_at DESC）
 *      完全在毫秒级返回，性能不亚于 Redis；
 *   3) 重启/迁移数据时纯 MySQL 持久化数据不丢；
 *   所以这里选择 MySQL 直接持久化 + 限长。后续如需 Redis（会话内热缓存）再叠加即可。
 */
@Service
public class ChatService {

    /** 每种 mode 保留最近消息数上限；50 条 ≈ 一轮完整对话的尺度 */
    public static final int MAX_HISTORY_PER_MODE = 50;

    private final ChatMessageRepository repo;

    public ChatService(ChatMessageRepository repo) {
        this.repo = repo;
    }

    /**
     * 获取某 mode 下最近 limit 条历史，按时间正序返回（UI 从上到下渲染）。
     * limit 超过 MAX 或 <=0 时按 MAX 取值，防止被拉爆。
     */
    public List<ChatMessage> getHistory(ChatMode mode, int limit) {
        int n = Math.max(1, Math.min(limit, MAX_HISTORY_PER_MODE));
        // 先按时间倒序取 top N（索引命中 idx_chat_mode_created）
        List<ChatMessage> latest = repo.findByModeOrderByCreatedAtDescIdDesc(
                mode, PageRequest.of(0, n));
        if (latest == null || latest.isEmpty()) {
            return Collections.emptyList();
        }
        // 反转为时间正序（最旧在顶部，最新在底部 = 和用户发送顺序一致）
        List<ChatMessage> asc = new ArrayList<>(latest);
        Collections.reverse(asc);
        return asc;
    }

    /**
     * 追加一条对话消息到某 mode 下；
     * 如果该 mode 累计消息数 > MAX_HISTORY_PER_MODE，则物理删除最旧的行，
     * 保证行数严格被约束（数据库不无限增长，也减少 Key 泄漏面）。
     *
     * @return 已持久化的消息实体（带 id / createdAt）
     */
    @Transactional
    public ChatMessage appendAndTrim(ChatMode mode, String role, String content) {
        if (content == null) content = "";
        ChatMessage saved = repo.save(new ChatMessage(mode, role, content));

        long total = repo.countByMode(mode);
        if (total > MAX_HISTORY_PER_MODE) {
            int excess = (int) (total - MAX_HISTORY_PER_MODE);
            List<Long> oldestIds = repo.findOldestIdsByMode(
                    mode, PageRequest.of(0, excess));
            if (oldestIds != null && !oldestIds.isEmpty()) {
                repo.deleteAllByIdInBatch(oldestIds);
            }
        }
        return saved;
    }
}
