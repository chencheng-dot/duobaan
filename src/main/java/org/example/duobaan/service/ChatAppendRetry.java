package org.example.duobaan.service;

import org.example.duobaan.model.ChatMessage;
import org.example.duobaan.model.ChatMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 对话写入的事务边界包装：把并发锁冲突的重试**放在事务之外**。
 *
 * 为什么要单独一个 bean：{@link ChatService#appendAndTrim} 抛
 * {@link PessimisticLockingFailureException} 时，Spring 的事务拦截器已把该事务标记为 rollback-only，
 * 在同一个事务内 catch 后重试只会得到已回滚的会话或 UnexpectedRollbackException。
 * 跨 bean 调用（而不是 ChatService 内部自调用）才能让每次重试都开一个全新事务。
 */
@Service
public class ChatAppendRetry {

    private static final Logger log = LoggerFactory.getLogger(ChatAppendRetry.class);

    private final ChatService chatService;

    public ChatAppendRetry(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 刻意**不加** @Transactional：重试必须发生在事务边界之外。
     * appendAndTrim 抛 CannotAcquireLockException 时它自己的事务已 rollback-only，
     * 在同一事务内重试等于白试。跨 bean 调用让它每次重试都开一个全新事务。
     */
    public ChatMessage appendAndTrimWithRetry(ChatMode mode, String role, String content) {
        try {
            return chatService.appendAndTrim(mode, role, content);
        } catch (PessimisticLockingFailureException e) {   // CannotAcquireLockException(1213) 与锁等待超时(1205) 都是它的子类
            log.warn("appendAndTrim 遇并发锁冲突，重试一次：mode={} role={}", mode, role, e);
            return chatService.appendAndTrim(mode, role, content);
        }
    }
}
