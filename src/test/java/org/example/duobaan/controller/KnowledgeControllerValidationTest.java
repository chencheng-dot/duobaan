package org.example.duobaan.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.example.duobaan.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识库接口入参校验的单元测试（不启动 Spring、不连库、不连 Redis）。
 *
 * <p>回归背景：T4 修复批把「检索词 2000 字符上限」误用到文档正文上，
 * 导致任何超过 2000 字符的文档在接口层就被 400 拒绝，Service 里 10MB 的上限形同虚设。
 * 本类同时锁定两条边界：检索词仍受字符数限制，文档正文只受体积限制。
 */
class KnowledgeControllerValidationTest {

    /** 正常长文（5 万字符，约 150KB UTF-8）必须放行——旧实现会在这里 400 */
    @Test
    void documentTextAcceptsLongDocument() {
        String doc = "知识库正文。".repeat(8_334); // 5 万字符，远超 2000 字符的查询上限
        assertTrue(doc.length() > 2_000);
        assertSame(doc, KnowledgeController.requireDocumentText(doc));
    }

    @Test
    void documentTextAcceptsTextExactlyAtSizeLimit() {
        // 纯 ASCII：字符数 == 字节数，正好卡在 10MB 边界，必须放行
        String doc = "a".repeat((int) KnowledgeService.MAX_DOC_BYTES);
        assertSame(doc, KnowledgeController.requireDocumentText(doc));
    }

    @Test
    void documentTextRejectsOver10Mb() {
        String doc = "a".repeat((int) KnowledgeService.MAX_DOC_BYTES + 1);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> KnowledgeController.requireDocumentText(doc));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatusCode());
    }

    @Test
    void documentTextRejectsNullAndBlank() {
        ResponseStatusException nullEx = assertThrows(ResponseStatusException.class,
                () -> KnowledgeController.requireDocumentText(null));
        assertEquals(HttpStatus.BAD_REQUEST, nullEx.getStatusCode());

        ResponseStatusException blankEx = assertThrows(ResponseStatusException.class,
                () -> KnowledgeController.requireDocumentText(" \n\t "));
        assertEquals(HttpStatus.BAD_REQUEST, blankEx.getStatusCode());
    }

    @Test
    void queryAcceptsUpTo2000Chars() {
        String query = "q".repeat(2_000);
        assertSame(query, KnowledgeController.requireQuery(query));
    }

    @Test
    void queryRejectsOverlong() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> KnowledgeController.requireQuery("q".repeat(2_001)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void queryRejectsNullAndBlank() {
        assertThrows(ResponseStatusException.class, () -> KnowledgeController.requireQuery(null));
        assertThrows(ResponseStatusException.class, () -> KnowledgeController.requireQuery("  "));
    }
}
