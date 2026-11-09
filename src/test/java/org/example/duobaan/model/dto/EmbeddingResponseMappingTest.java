package org.example.duobaan.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * /embeddings 响应中 {@code data[].index} 的映射测试。
 *
 * <p>背景：部分服务商不保证返回顺序与请求顺序一致。旧实现忽略 index、只按数组位置落位，
 * 一旦乱序就会把 A 分块的向量写到 B 分块上，检索表现为「答非所问」且无任何日志。
 * 这里锁定 DTO 必须能读到 index。
 */
class EmbeddingResponseMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsIndexAndEmbedding() throws Exception {
        String json = """
                {"object":"list","data":[
                  {"object":"embedding","index":1,"embedding":[0.1,0.2]},
                  {"object":"embedding","index":0,"embedding":[0.3,0.4]}
                ],"model":"text-embedding","usage":{"prompt_tokens":3,"total_tokens":3}}
                """;
        EmbeddingResponse resp = objectMapper.readValue(json, EmbeddingResponse.class);
        assertEquals(2, resp.data().size());
        assertEquals(1, resp.data().get(0).index());
        assertEquals(List.of(0.1, 0.2), resp.data().get(0).embedding());
        assertEquals(0, resp.data().get(1).index());
    }

    @Test
    void toleratesMissingIndex() throws Exception {
        String json = "{\"data\":[{\"embedding\":[0.5]}]}";
        EmbeddingResponse resp = objectMapper.readValue(json, EmbeddingResponse.class);
        assertEquals(1, resp.data().size());
        assertEquals(null, resp.data().get(0).index());
    }
}
