package org.example.duobaan.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiProfileService#isMaskedKey(String)} / {@link ApiProfileService#maskKey(String)} 单元测试。
 *
 * <p>包级可见的 static 方法，测试类与被测类同包；不启动 Spring 上下文、不碰数据库。
 * 这两个方法的用途：前端回传的打码 Key 绝不能写库（写进去会把真 Key 覆盖成假串，之后全部 401）。
 */
@DisplayName("ApiProfileService.isMaskedKey —— 打码 Key 识别")
class ApiProfileServiceMaskedKeyTest {

    @Test
    @DisplayName("null / 空串 / 纯空白不是打码 Key")
    void nullEmptyBlankAreNotMasked() {
        assertAll(
                () -> assertFalse(ApiProfileService.isMaskedKey(null)),
                () -> assertFalse(ApiProfileService.isMaskedKey("")),
                () -> assertFalse(ApiProfileService.isMaskedKey("   ")),
                () -> assertFalse(ApiProfileService.isMaskedKey("\t\n"))
        );
    }

    @Test
    @DisplayName("含 **** 的打码形态一律识别为打码 Key")
    void keysContainingFourStarsAreMasked() {
        assertAll(
                () -> assertTrue(ApiProfileService.isMaskedKey("abcd****wxyz")),
                () -> assertTrue(ApiProfileService.isMaskedKey("****")),
                () -> assertTrue(ApiProfileService.isMaskedKey("********")),
                () -> assertTrue(ApiProfileService.isMaskedKey("a****b")),
                () -> assertTrue(ApiProfileService.isMaskedKey("****abcd")),
                () -> assertTrue(ApiProfileService.isMaskedKey("abcdefgh****")),
                // 真实实现会先 trim，前后空格不影响判定
                () -> assertTrue(ApiProfileService.isMaskedKey("  abcd****wxyz  "))
        );
    }

    @Test
    @DisplayName("全星号串（任意长度，哪怕是 * 或 **）也算打码 Key（当前真实行为）")
    void allStarStringsAreMasked() {
        // 注意：判据是「不含非 '*' 字符」，不是「长度 >= 4 或必须出现 ****」，
        // 所以 "*"、"**" 也会被判为打码值。真实 API Key 不可能只由 '*' 组成，故不会误伤。
        assertAll(
                () -> assertTrue(ApiProfileService.isMaskedKey("*")),
                () -> assertTrue(ApiProfileService.isMaskedKey("**")),
                () -> assertTrue(ApiProfileService.isMaskedKey("***")),
                () -> assertTrue(ApiProfileService.isMaskedKey("*****"))
        );
    }

    @Test
    @DisplayName("真实 Key 形态不是打码 Key")
    void realKeysAreNotMasked() {
        assertAll(
                () -> assertFalse(ApiProfileService.isMaskedKey("sk-1234567890abcdef")),
                // 只有一个星号：不满足 ****，而循环一开始就遇到非 '*' 字符 → false
                () -> assertFalse(ApiProfileService.isMaskedKey("abcd*wxyz")),
                () -> assertFalse(ApiProfileService.isMaskedKey("sk-proj-abc*def")),
                () -> assertFalse(ApiProfileService.isMaskedKey("dashscope-key-with-***-inside"))
        );
    }

    @Test
    @DisplayName("maskKey 的产物必须被 isMaskedKey 认出来（回环一致性）")
    void maskKeyOutputRoundTripsAsMasked() {
        assertAll(
                () -> assertEquals("sk-1****cdef", ApiProfileService.maskKey("sk-1234567890abcdef")),
                () -> assertTrue(ApiProfileService.isMaskedKey(ApiProfileService.maskKey("sk-1234567890abcdef"))),
                // 长度 < 8 时兜底为 "****"
                () -> assertEquals("****", ApiProfileService.maskKey("short")),
                () -> assertTrue(ApiProfileService.isMaskedKey(ApiProfileService.maskKey("short"))),
                // 空 Key 打码为空串，空串不算打码 Key（否则会把「清空 Key」误判为回传打码值）
                () -> assertEquals("", ApiProfileService.maskKey("")),
                () -> assertFalse(ApiProfileService.isMaskedKey(ApiProfileService.maskKey(""))),
                () -> assertEquals("", ApiProfileService.maskKey(null))
        );
    }
}
