package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.ChatMessage;
import org.example.duobaan.model.ChatMode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByModeOrderByCreatedAtAsc(ChatMode mode);

    /** 取最近 limit 条（按时间倒序取 top N，调用方再反转成正序渲染） */
    List<ChatMessage> findByModeOrderByCreatedAtDescIdDesc(ChatMode mode, Pageable pageable);

    /** 统计某 mode 下消息总数，用于 trim 判断 */
    long countByMode(ChatMode mode);

    /** 取最旧的 excess 条消息 id，用于删除时定位要删的行（避免一次性全表扫描） */
    @Query("SELECT c.id FROM ChatMessage c WHERE c.mode = :mode ORDER BY c.createdAt ASC, c.id ASC")
    List<Long> findOldestIdsByMode(@Param("mode") ChatMode mode, Pageable pageable);
}
