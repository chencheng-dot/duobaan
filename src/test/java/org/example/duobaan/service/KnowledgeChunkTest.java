package org.example.duobaan.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeService#chunk(String, String, int, int)} 四种分块策略 + 边界参数的纯函数单元测试。
 *
 * 覆盖：
 *   - 空/空白输入 → 空列表（不抛异常）
 *   - PARAGRAPH / SENTENCE / MARKDOWN / SIMPLE 四种策略各自的最小行为
 *   - 边界：chunkSize < 50 回落到 500、chunkOverlap < 0 归零、chunkOverlap 超过 size/2 被钳制
 *   - 未知策略 → 默认按段落
 */
@DisplayName("KnowledgeService.chunk —— 分块纯函数")
class KnowledgeChunkTest {

    @Test
    @DisplayName("空 / null / 纯空白输入返回空列表")
    void blankInputReturnsEmpty() {
        assertAll(
                () -> assertTrue(KnowledgeService.chunk(null, "PARAGRAPH", 500, 50).isEmpty()),
                () -> assertTrue(KnowledgeService.chunk("", "PARAGRAPH", 500, 50).isEmpty()),
                () -> assertTrue(KnowledgeService.chunk("   \n\t  ", "SENTENCE", 500, 50).isEmpty())
        );
    }

    @Test
    @DisplayName("PARAGRAPH：短文本（含多个空行段落）聚合成 1 块")
    void paragraphShortTextOneChunk() {
        List<String> chunks = KnowledgeService.chunk("第一段\n\n第二段\n\n第三段", "PARAGRAPH", 500, 50);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段") && chunks.get(0).contains("第三段"));
    }

    @Test
    @DisplayName("PARAGRAPH：单段落超长时按 size 硬切（无 overlap → 每块恰为 size，最后一块为余量）")
    void paragraphLongTextSplitsBySize() {
        List<String> chunks = KnowledgeService.chunk("x".repeat(250), "PARAGRAPH", 100, 0);
        assertEquals(3, chunks.size());
        assertAll(
                () -> assertEquals(100, chunks.get(0).length()),
                () -> assertEquals(100, chunks.get(1).length()),
                () -> assertEquals(50, chunks.get(2).length())
        );
    }

    @Test
    @DisplayName("SENTENCE：按中英文句末标点切分后聚合，短文本为 1 块且不丢句子")
    void sentenceAggregatesAllShortSentences() {
        List<String> chunks = KnowledgeService.chunk("第一句。第二句！第三句？", "SENTENCE", 500, 0);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一句") && chunks.get(0).contains("第三句"));
    }

    @Test
    @DisplayName("SENTENCE：超长文本切出多块且无空块")
    void sentenceLongTextSplits() {
        List<String> chunks = KnowledgeService.chunk("这是一句测试文本。".repeat(30), "SENTENCE", 100, 0);
        assertTrue(chunks.size() >= 2, "应切出多块，实际 " + chunks.size());
        assertFalse(chunks.stream().anyMatch(String::isBlank));
    }

    @Test
    @DisplayName("MARKDOWN：按 #/##/### 标题切分，标题数与块数一致且每块以标题开头")
    void markdownSplitsByHeading() {
        String md = "# 标题一\n内容一\n\n## 标题二\n内容二\n\n### 标题三\n内容三";
        List<String> chunks = KnowledgeService.chunk(md, "MARKDOWN", 500, 0);
        assertEquals(3, chunks.size());
        assertAll(
                () -> assertTrue(chunks.get(0).startsWith("# 标题一")),
                () -> assertTrue(chunks.get(1).startsWith("## 标题二")),
                () -> assertTrue(chunks.get(2).startsWith("### 标题三"))
        );
    }

    /** SIMPLE 用例素材：100 字符（注意 chunkSize 必须 ≥ 50，否则会被回落到 500） */
    private static final String HUNDRED = "abcdefghij".repeat(10);

    @Test
    @DisplayName("SIMPLE：固定长度硬切 + overlap，步长 = size - overlap")
    void simpleHardCutWithOverlap() {
        // size=60, overlap=20 → step=40；100 字符 → [0,60) 与 [40,100) 两块，均长 60
        List<String> chunks = KnowledgeService.chunk(HUNDRED, "SIMPLE", 60, 20);
        assertEquals(2, chunks.size());
        assertAll(
                () -> assertEquals(60, chunks.get(0).length()),
                () -> assertEquals(60, chunks.get(1).length()),
                // 第二块前 20 字符应等于第一块末尾 20 字符（重叠生效）
                () -> assertEquals(chunks.get(0).substring(40), chunks.get(1).substring(0, 20))
        );
    }

    @Test
    @DisplayName("边界：chunkSize < 50 回落到 500（200 字符仅 1 块）")
    void smallChunkSizeFallsBackTo500() {
        List<String> chunks = KnowledgeService.chunk("x".repeat(200), "SIMPLE", 40, 0);
        assertEquals(1, chunks.size());
        assertEquals(200, chunks.get(0).length());
    }

    @Test
    @DisplayName("边界：chunkOverlap 超过 size/2 时被钳制（step 不会退化为 0）")
    void overlapIsClampedToHalfSize() {
        // SIMPLE size=60, 传 overlap=100 → 钳制为 30 → step=30；100 字符 → 0-60 / 30-90 / 60-100 → 3 块
        List<String> chunks = KnowledgeService.chunk(HUNDRED, "SIMPLE", 60, 100);
        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> !c.isBlank() && c.length() <= 60));
    }

    @Test
    @DisplayName("边界：chunkOverlap 为负数时按 0 处理")
    void negativeOverlapBecomesZero() {
        // size=60, overlap=-5 → 0 → step=60；100 字符 → [0,60) 与 [60,100) 两块
        List<String> chunks = KnowledgeService.chunk(HUNDRED, "SIMPLE", 60, -5);
        assertEquals(2, chunks.size());
        assertAll(
                () -> assertEquals(60, chunks.get(0).length()),
                () -> assertEquals(40, chunks.get(1).length())
        );
    }

    @Test
    @DisplayName("未知 / null 策略回落到 PARAGRAPH")
    void unknownStrategyFallsBackToParagraph() {
        String text = "第一段\n\n第二段";
        assertEquals(
                KnowledgeService.chunk(text, "PARAGRAPH", 500, 0),
                KnowledgeService.chunk(text, "NOT_A_STRATEGY", 500, 0));
        assertEquals(
                KnowledgeService.chunk(text, "PARAGRAPH", 500, 0),
                KnowledgeService.chunk(text, null, 500, 0));
    }
}
