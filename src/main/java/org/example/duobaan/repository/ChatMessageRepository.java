package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.ChatMessage;
import org.example.duobaan.model.ChatMode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByModeOrderByCreatedAtAsc(ChatMode mode);

    /** 取最近 limit 条（按时间倒序取 top N，调用方再反转成正序渲染） */
    List<ChatMessage> findByModeOrderByCreatedAtDescIdDesc(ChatMode mode, Pageable pageable);

    /**
     * 取超出保留额度的行 id（第 keep+1 新及更旧），一次最多 limit 条。
     * LIMIT / OFFSET 都不接受表达式，两个值都必须由 Java 侧算好传入。
     *
     * 为什么不再用范围删除：旧版 `DELETE … WHERE mode=:mode AND id < :threshold` 经 `EXPLAIN` 实测
     * 走 `idx_chat_mode_created`、`key_len=1` —— 只用到 mode 一列、`id < threshold` 是取行后过滤（Using where），
     * 即仍按 mode 全扫、仍会「遇到」并发新插入的行并对其请求锁，1213 死锁 / 500 丢消息这条危害未消除。
     * 改为「先取 id 列表、再按主键删」后，删除退化为若干主键等值点查，不再扫任何区间。
     *
     * ⚠️ 与读路径口径仍未统一：trim 按 id DESC，读路径按 created_at DESC, id DESC；createdAt 由 Java 侧赋值，
     * 并发下 id 序 ≠ 时间序（根治：让 created_at 由 DB 生成，使 id 与时间同源）。见 P2-D5。
     * mode 以 name() 字符串传入；底层列是 ENUM('DOPAMINE','WORK')（见 schema.sql），
     * 新增枚举值时必须同步改 DDL，别以为只改 Java 枚举就够了。
     */
    @Query(value = """
            SELECT id FROM chat_message
            WHERE mode = :mode
            ORDER BY id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Integer> findTrimIds(@Param("mode") String mode, @Param("offset") int offset, @Param("limit") int limit);

    /** 只按主键删：等值点查 → 只对这几行加记录锁，不扫描新插入行所在区间（这是消除死锁的关键）。 */
    @Modifying
    @Query(value = "DELETE FROM chat_message WHERE id IN (:ids)", nativeQuery = true)
    int deleteByIds(@Param("ids") List<Integer> ids);
}
