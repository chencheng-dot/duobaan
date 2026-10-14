package org.example.duobaan.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UrlSanitizer} 单元测试。
 *
 * <p>断言的是<b>当前真实行为</b>（先跑探针确认），不是期望行为；发现的可疑点只以
 * {@code BUG} 注释标注，不改主代码。
 */
@DisplayName("UrlSanitizer —— URL/文本清洗（断言当前真实行为）")
class UrlSanitizerTest {

    private static final String OPENAI_BASE = "https://api.openai.com/v1";
    private static final String DASH_BASE = "https://dashscope.aliyuncs.com/api/v1";

    // ==================== sanitizeBaseUrl：入参边界 ====================

    @Test
    @DisplayName("null 进 null 出；空串/纯空白进则返回空串（不是 null）")
    void sanitizeBaseUrl_nullInNullOut_blankBecomesEmpty() {
        assertAll(
                () -> assertNull(UrlSanitizer.sanitizeBaseUrl(null)),
                () -> assertEquals("", UrlSanitizer.sanitizeBaseUrl("")),
                () -> assertEquals("", UrlSanitizer.sanitizeBaseUrl("   ")),
                () -> assertEquals("", UrlSanitizer.sanitizeBaseUrl("\t\n")),
                () -> assertEquals("", UrlSanitizer.sanitizeBaseUrl("\u00A0"))
        );
    }

    // ==================== sanitizeBaseUrl：Markdown 包络清洗 ====================

    @Test
    @DisplayName("剥掉 Markdown/引用包络：> `url`、`url`、[url]、首尾空白")
    void sanitizeBaseUrl_stripsMarkdownEnvelope() {
        assertAll(
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl("> `" + OPENAI_BASE + "`")),
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl("`" + OPENAI_BASE + "`")),
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl("[" + OPENAI_BASE + "]")),
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl("  " + OPENAI_BASE + "  ")),
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl("\u00A0" + OPENAI_BASE + "\u00A0"))
        );
    }

    @Test
    @DisplayName("历史脏数据：反引号后的 platform.q 等拼接垃圾被截断，随后 action 后缀也被裁掉")
    void sanitizeBaseUrl_cutsMarkdownGarbageAfterBacktick() {
        String dirty = "> `" + DASH_BASE + "/services/aigc/video-generation/video-synthesis`platform.q";
        // 修复 startFrom 之后，末尾这个 services/aigc/... 后缀会被 ACTION_SUFFIX 一并裁掉，
        // 这正是「用户把完整 endpoint 当 baseUrl 粘进来」时想要的结果（endpoint 由服务内部自己拼）。
        assertEquals(DASH_BASE, UrlSanitizer.sanitizeBaseUrl(dirty));
    }

    @Test
    @DisplayName("遇到第一个非 ASCII（中文/全角括号）字符即截断")
    void sanitizeBaseUrl_cutsAtFirstNonAsciiChar() {
        assertAll(
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "（备用）")),
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "\u4e2d\u6587"))
        );
    }

    // ==================== sanitizeBaseUrl：尾部 action 路径裁剪 ====================

    @Test
    @DisplayName("单段 action 后缀被裁剪：completions / embeddings / models")
    void sanitizeBaseUrl_stripsSingleSegmentActionSuffix() {
        assertAll(
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "/completions")),
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "/embeddings")),
                () -> assertEquals(OPENAI_BASE, UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "/models"))
        );
    }

    @Test
    @DisplayName("多段 action 后缀现在会被正确裁剪（原 bug 已修复）")
    void sanitizeBaseUrl_stripsNestedActionSuffix() {
        // 修复前：UrlSanitizer.java:105 `startFrom = s.lastIndexOf('/')` 让所有含 "/" 的分支永远匹配不上，
        // 于是「用户把 endpoint 当 baseUrl 粘进来」这一最主要场景根本没被清洗。
        // 修复后（改为 m.find() 从头搜，靠正则自带的 $ 锚点保证对齐）这些后缀都被裁掉。
        assertAll(
                () -> assertEquals(OPENAI_BASE,
                        UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "/videos/generations")),
                () -> assertEquals(OPENAI_BASE,
                        UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "/images/generations")),
                () -> assertEquals(OPENAI_BASE,
                        UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "/audio/speech")),
                () -> assertEquals(OPENAI_BASE,
                        UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "/audio/transcriptions")),
                () -> assertEquals(DASH_BASE,
                        UrlSanitizer.sanitizeBaseUrl(DASH_BASE + "/services/aigc/video-generation/video-synthesis")),
                () -> assertEquals(DASH_BASE,
                        UrlSanitizer.sanitizeBaseUrl(DASH_BASE + "/services/aigc/multimodal-generation/generation"))
        );
    }

    @Test
    @DisplayName("chat/completions 被整体裁掉，不再留下悬挂的 .../chat（原 bug 已修复）")
    void sanitizeBaseUrl_chatCompletionsStripsWholeSuffix() {
        // 修复前：startFrom 落在最后一个 '/' 上，正则退化成先命中单段分支 "completions"，
        // 砍掉 "/completions" 却留下 "/chat"，产出一个必然 404 的错误 baseUrl。
        assertEquals(OPENAI_BASE,
                UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "/chat/completions"));
    }

    // ==================== sanitizeBaseUrl：尾部斜杠 ====================

    @Test
    @DisplayName("重复尾部斜杠收敛为 1 个；单个尾部斜杠保留")
    void sanitizeBaseUrl_collapsesDuplicateTrailingSlashes() {
        assertAll(
                () -> assertEquals(OPENAI_BASE + "/", UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "//")),
                () -> assertEquals(OPENAI_BASE + "/", UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "///")),
                () -> assertEquals(OPENAI_BASE + "/", UrlSanitizer.sanitizeBaseUrl(OPENAI_BASE + "/")),
                () -> assertEquals(OPENAI_BASE + "/", UrlSanitizer.sanitizeBaseUrl("  " + OPENAI_BASE + "/  "))
        );
    }

    // ==================== sanitizeBaseUrl：不做校验 ====================

    @Test
    @DisplayName("不做 scheme/host 校验：非法或内网地址被原样（或截断后）返回")
    void sanitizeBaseUrl_doesNotValidateSchemeOrHost() {
        assertAll(
                // "not a url" 在第一个空格处被截断；不抛异常、不返回 null
                () -> assertEquals("not", UrlSanitizer.sanitizeBaseUrl("not a url")),
                () -> assertEquals("http://127.0.0.1:8080/x", UrlSanitizer.sanitizeBaseUrl("http://127.0.0.1:8080/x")),
                () -> assertEquals("file:///etc/passwd", UrlSanitizer.sanitizeBaseUrl("file:///etc/passwd"))
        );
    }

    // ==================== cleanText ====================

    @Test
    @DisplayName("cleanText：null/空串/纯空白一律返回 null（trimToNull 语义）")
    void cleanText_nullAndBlankBecomeNull() {
        assertAll(
                () -> assertNull(UrlSanitizer.cleanText(null)),
                () -> assertNull(UrlSanitizer.cleanText("")),
                () -> assertNull(UrlSanitizer.cleanText("   ")),
                () -> assertNull(UrlSanitizer.cleanText("\t\n"))
        );
    }

    @Test
    @DisplayName("cleanText：有内容则只 trim 首尾，保留内部换行")
    void cleanText_trimsRealText() {
        assertAll(
                () -> assertEquals("abc", UrlSanitizer.cleanText("abc")),
                () -> assertEquals("abc", UrlSanitizer.cleanText("  abc  ")),
                () -> assertEquals("a\nb", UrlSanitizer.cleanText("a\nb")),
                () -> assertEquals("a\nb", UrlSanitizer.cleanText("  a\nb  "))
        );
    }

    @Test
    @DisplayName("cleanText：NBSP(0xA0) 既不算空白也不会被 trim 掉（与 sanitizeBaseUrl 不一致）")
    void cleanText_doesNotTreatNbspAsBlank_BUG() {
        // BUG: 见审查报告 P2 —— UrlSanitizer.java:142 `return s == null ? null : null;` 是恒为 null 的
        // 冗余三元表达式（功能上等价于 trimToNull，行为正确但代码有误导性）。
        // 另外这里记录一个真实差异：StringUtils.hasText 用 Character.isWhitespace 判空，NBSP 不是
        // whitespace，而 String.trim() 只去 <= U+0020 的字符，所以「看起来全空白」的 NBSP 串会原样返回；
        // 而 sanitizeBaseUrl 因为 ENVELOPE_CHARS 里含 NBSP，会把它清成空串。
        assertAll(
                () -> assertEquals("\u00A0x\u00A0", UrlSanitizer.cleanText("\u00A0x\u00A0")),
                () -> assertEquals("\u00A0", UrlSanitizer.cleanText("\u00A0")),
                () -> assertEquals("", UrlSanitizer.sanitizeBaseUrl("\u00A0"))
        );
    }
}
