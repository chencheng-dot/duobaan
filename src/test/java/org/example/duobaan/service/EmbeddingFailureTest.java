package org.example.duobaan.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;

/**
 * 向量化失败归类与文案的单元测试（不启动 Spring、不发网络请求）。
 *
 * <p>覆盖背景：2026-10 的 T4 运行时验证发现「embedding 免费额度耗尽（HTTP 400
 * AllocationQuota.FreeTierOnly）→ 检索永远为空 → 界面只显示『就绪』」。
 * 修复的核心是把这类失败显式归类并翻译成用户能看懂的原因，本类锁定该翻译逻辑。
 */
class EmbeddingFailureTest {

    // ---------- HTTP 状态码归类 ----------

    @Test
    void quotaBodyBeats400() {
        // 关键回归：400 + 额度关键字必须判为「额度用尽」，而不是笼统的「请求被拒绝」
        EmbeddingService.Mapping m = EmbeddingService.classifyHttpStatus(
                400, "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"free quota exhausted\"}}");
        assertEquals(EmbeddingService.FailureKind.QUOTA_EXHAUSTED, m.kind());
        assertTrue(m.message().contains("额度"));
        assertFalse(m.message().toLowerCase().contains("freetier"), "用户文案不应回吐厂商原始错误码");
    }

    @Test
    void authFailed() {
        assertEquals(EmbeddingService.FailureKind.AUTH_FAILED,
                EmbeddingService.classifyHttpStatus(401, "{\"error\":\"invalid api key\"}").kind());
    }

    @Test
    void rateLimited() {
        assertEquals(EmbeddingService.FailureKind.RATE_LIMITED,
                EmbeddingService.classifyHttpStatus(429, "").kind());
    }

    @Test
    void badRequest() {
        assertEquals(EmbeddingService.FailureKind.BAD_REQUEST,
                EmbeddingService.classifyHttpStatus(422, "").kind());
    }

    @Test
    void upstreamUnavailable() {
        assertEquals(EmbeddingService.FailureKind.UPSTREAM_UNAVAILABLE,
                EmbeddingService.classifyHttpStatus(503, "").kind());
    }

    // ---------- 网络异常归类 ----------

    @Test
    void timeoutIsNetworkError() {
        assertEquals(EmbeddingService.FailureKind.NETWORK_ERROR,
                EmbeddingService.classifyThrowable(new SocketTimeoutException("Read timed out")).kind());
    }

    @Test
    void connectExceptionIsNetworkError() {
        assertEquals(EmbeddingService.FailureKind.NETWORK_ERROR,
                EmbeddingService.classifyThrowable(new ConnectException("Connection refused")).kind());
    }

    @Test
    void unknownExceptionDoesNotLeakMessage() {
        EmbeddingService.Mapping m = EmbeddingService.classifyThrowable(new IllegalStateException("secret internal state"));
        assertEquals(EmbeddingService.FailureKind.UNKNOWN, m.kind());
        assertFalse(m.message().contains("secret"), "未知异常文案不得包含内部异常原文");
    }

    // ---------- 额度关键字判定 ----------

    @Test
    void looksLikeQuota() {
        assertTrue(EmbeddingService.looksLikeQuota("insufficient_quota"));
        assertTrue(EmbeddingService.looksLikeQuota("账户余额不足，请充值"));
        assertFalse(EmbeddingService.looksLikeQuota(null));
        assertFalse(EmbeddingService.looksLikeQuota("model not found"));
    }

    // ---------- 失败记录与用户后缀 ----------

    @Test
    void failureSuffixWithoutFailureGivesGenericTip() {
        // 构造器依赖运行时通常非空，但 failureSuffix 只读内部状态；传 null 是安全的
        EmbeddingService service = new EmbeddingService(null, null);
        assertTrue(service.lastFailure().isEmpty());
        assertTrue(service.failureSuffix().contains("检查"));
    }
}
