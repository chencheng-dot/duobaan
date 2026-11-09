package org.example.duobaan.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OpenAI 兼容协议的 /embeddings 响应结构。
 *
 * <p>保留 {@code data[].index}：部分服务商不保证返回顺序与请求顺序一致，
 * 只按数组位置落位会把 A 分块的向量写到 B 分块上（检索时「答非所问」），
 * 因此向量必须按 index 归位，index 缺失时才退回数组顺序。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingResponse(List<EmbeddingData> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingData(Integer index, List<Double> embedding) {
    }
}