package org.example.duobaan.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.example.duobaan.model.dto.RagStatus;
import org.junit.jupiter.api.Test;

/**
 * RAG 状态判定与入库降级说明的单元测试（不启动 Spring、不连库、不连 Redis）。
 *
 * <p>覆盖背景：旧实现只看「配置存在 + Redis 连通」就报「就绪」，
 * 即使 MySQL 里有 935 个分块、Redis 里 0 个向量也照报就绪，检索静默返回空。
 * 本类锁定「分块数 vs 向量数」的降级口径，防止回归。
 */
class KnowledgeStatusTest {

    private static RagStatus status(boolean emb, boolean redis, int kb, long chunks, long vectors) {
        return new RagStatus(emb, redis, kb, chunks, vectors, false, null);
    }

    @Test
    void degradedWhenChunksExistButVectorsEmpty() {
        // T4 现场：935 个分块、0 个向量，却被报「就绪」
        assertTrue(KnowledgeService.degraded(status(true, true, 5, 935, 0)));
    }

    @Test
    void degradedWhenPartiallyVectorized() {
        assertTrue(KnowledgeService.degraded(status(true, true, 1, 100, 40)));
    }

    @Test
    void notDegradedWhenCountsMatch() {
        assertFalse(KnowledgeService.degraded(status(true, true, 1, 100, 100)));
    }

    @Test
    void notDegradedWhenNoChunks() {
        assertFalse(KnowledgeService.degraded(status(true, true, 0, 0, 0)));
    }

    @Test
    void degradedWhenEmbeddingNotReady() {
        assertTrue(KnowledgeService.degraded(status(false, true, 1, 10, 10)));
    }

    @Test
    void degradedWhenRedisDown() {
        assertTrue(KnowledgeService.degraded(status(true, false, 1, 10, 10)));
    }

    @Test
    void degradedWhenRedisDownEvenWithoutChunks() {
        // 没有分块也要报降级：否则「Redis 没启动 + 空库」会显示「就绪」，用户以为检索可用
        assertTrue(KnowledgeService.degraded(status(true, false, 0, 0, 0)));
    }

    // ---------- 降级说明的判定顺序 ----------

    @Test
    void hintPrefersRedisDownOverMissingVectors() {
        // Redis 不通时 vectorCount 必然为 0；提示必须指向「Redis 没启动」，
        // 不能误报成「向量丢了，请重建」，那会把用户引向错误的修复动作
        String hint = KnowledgeService.hintFor(true, false, 935, 0);
        assertNotNull(hint);
        assertTrue(hint.contains("Redis 未连通"));
        assertFalse(hint.contains("重建向量"));
    }

    @Test
    void noHintWhenComplete() {
        assertNull(KnowledgeService.hintFor(true, true, 100, 100));
    }

    @Test
    void noHintWhenNothingUploaded() {
        assertNull(KnowledgeService.hintFor(true, true, 0, 0));
    }

    @Test
    void hintWhenEmbeddingNotReady() {
        String hint = KnowledgeService.hintFor(false, true, 10, 10);
        assertNotNull(hint);
        assertTrue(hint.contains("向量化模型未就绪"));
    }

    @Test
    void hintWhenNoVectorsAtAll() {
        String hint = KnowledgeService.hintFor(true, true, 935, 0);
        assertNotNull(hint);
        assertTrue(hint.contains("935"));
        assertTrue(hint.contains("一个向量都没有"));
    }

    @Test
    void hintWhenVectorsPartiallyMissing() {
        String hint = KnowledgeService.hintFor(true, true, 935, 900);
        assertNotNull(hint);
        assertTrue(hint.contains("900"));
        assertTrue(hint.contains("缺失 35"));
    }

    /** degraded 与 hintFor 必须同口径：有提示 == 报降级，否则前端会「提示了但不标降级」 */
    @Test
    void degradedAgreesWithHint() {
        boolean[][] flags = {{true, true}, {true, false}, {false, true}};
        long[][] counts = {{0, 0}, {10, 10}, {10, 0}, {10, 7}, {935, 0}};
        for (boolean[] f : flags) {
            for (long[] c : counts) {
                RagStatus s = new RagStatus(f[0], f[1], 1, c[0], c[1], false, null);
                String hint = KnowledgeService.hintFor(f[0], f[1], c[0], c[1]);
                assertEquals(hint != null, KnowledgeService.degraded(s),
                        "embeddingReady=" + f[0] + ", redisOk=" + f[1] + ", chunks=" + c[0] + ", vectors=" + c[1]);
            }
        }
    }

    // ---------- 入库结果的降级说明 ----------

    @Test
    void noHintWhenEverythingVectorized() {
        assertNull(KnowledgeChunkWriter.buildHint(10, 10, true, null));
    }

    @Test
    void noHintWhenNothingWritten() {
        assertNull(KnowledgeChunkWriter.buildHint(0, 0, true, null));
    }

    @Test
    void hintWhenEmbeddingNotConfigured() {
        String hint = KnowledgeChunkWriter.buildHint(10, 0, false, null);
        assertNotNull(hint);
        assertTrue(hint.contains("未配置"));
        assertTrue(hint.contains("重建向量"));
    }

    @Test
    void hintWhenAllVectorsFailed() {
        String hint = KnowledgeChunkWriter.buildHint(10, 0, true, "最近一次失败：额度已用尽");
        assertNotNull(hint);
        assertTrue(hint.contains("全部失败"));
        assertTrue(hint.contains("额度已用尽"));
    }

    @Test
    void hintWhenPartiallyVectorized() {
        String hint = KnowledgeChunkWriter.buildHint(10, 4, true, null);
        assertNotNull(hint);
        assertTrue(hint.contains("4/10"));
    }

    // ---------- 分块基础行为 ----------

    @Test
    void chunkSimpleNeverReturnsBlankItems() {
        List<String> parts = KnowledgeService.chunk("第一段。第二段。", "SIMPLE", 50, 0);
        assertNotNull(parts);
        assertFalse(parts.isEmpty());
        assertTrue(parts.stream().noneMatch(String::isBlank));
    }
}
