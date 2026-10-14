package org.example.duobaan.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeService#cosine(float[], float[])} 纯计算单元测试（包级可见，无需 Spring 上下文）。
 * 检索排序完全依赖这个相似度，改了必须先跑这里。
 */
@DisplayName("KnowledgeService.cosine —— 余弦相似度纯函数")
class KnowledgeCosineTest {

    private static final double DELTA = 1e-6;

    @Test
    @DisplayName("相同向量 ≈ 1.0")
    void identicalVectorsAreOne() {
        assertAll(
                () -> assertEquals(1.0, KnowledgeService.cosine(new float[]{1f, 2f, 3f}, new float[]{1f, 2f, 3f}), DELTA),
                () -> assertEquals(1.0, KnowledgeService.cosine(new float[]{0.5f}, new float[]{0.5f}), DELTA),
                () -> assertEquals(1.0, KnowledgeService.cosine(new float[]{-1f, -2f}, new float[]{-1f, -2f}), DELTA)
        );
    }

    @Test
    @DisplayName("正交向量 ≈ 0.0")
    void orthogonalVectorsAreZero() {
        assertAll(
                () -> assertEquals(0.0, KnowledgeService.cosine(new float[]{1f, 0f}, new float[]{0f, 1f}), DELTA),
                () -> assertEquals(0.0, KnowledgeService.cosine(new float[]{1f, 2f}, new float[]{2f, -1f}), DELTA),
                () -> assertEquals(0.0, KnowledgeService.cosine(new float[]{1f, 0f, 0f}, new float[]{0f, 0f, 1f}), DELTA)
        );
    }

    @Test
    @DisplayName("方向相反的向量 ≈ -1.0")
    void oppositeVectorsAreMinusOne() {
        assertEquals(-1.0, KnowledgeService.cosine(new float[]{1f, 0f}, new float[]{-1f, 0f}), DELTA);
    }

    @Test
    @DisplayName("维度不一致返回 0（不抛异常）")
    void dimensionMismatchReturnsZero() {
        assertAll(
                () -> assertEquals(0.0, KnowledgeService.cosine(new float[]{1f, 0f}, new float[]{1f, 0f, 0f}), DELTA),
                () -> assertEquals(0.0, KnowledgeService.cosine(new float[]{1f}, new float[0]), DELTA)
        );
    }

    @Test
    @DisplayName("任一为零向量返回 0（不产生 NaN）")
    void zeroVectorReturnsZero() {
        assertAll(
                () -> assertEquals(0.0, KnowledgeService.cosine(new float[]{0f, 0f}, new float[]{1f, 1f}), DELTA),
                () -> assertEquals(0.0, KnowledgeService.cosine(new float[]{1f, 1f}, new float[]{0f, 0f}), DELTA),
                () -> assertEquals(0.0, KnowledgeService.cosine(new float[]{0f, 0f}, new float[]{0f, 0f}), DELTA)
        );
    }

    @Test
    @DisplayName("null 入参返回 0（不抛异常）")
    void nullReturnsZero() {
        assertAll(
                () -> assertEquals(0.0, KnowledgeService.cosine(null, new float[]{1f}), DELTA),
                () -> assertEquals(0.0, KnowledgeService.cosine(new float[]{1f}, null), DELTA),
                () -> assertEquals(0.0, KnowledgeService.cosine(null, null), DELTA)
        );
    }

    @Test
    @DisplayName("缩放不变：同方向不同长度仍是 1.0")
    void scaleInvariant() {
        assertEquals(1.0, KnowledgeService.cosine(new float[]{1f, 2f, 3f}, new float[]{2f, 4f, 6f}), DELTA);
    }
}
