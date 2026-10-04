package org.example.duobaan.model.dto;

/**
 * 检索命中结果：一段被召回的分块 + 来源知识库 + 相似度得分。
 * 用于「测试检索」接口返回，也让注入 LLM 的上下文可溯源。
 */
public record RetrievedChunk(
        Long kbId,
        String kbName,
        Long chunkId,
        String content,
        double score) {
}