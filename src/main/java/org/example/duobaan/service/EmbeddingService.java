package org.example.duobaan.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.duobaan.model.ApiProfile;
import org.example.duobaan.model.ApiProfileType;
import org.example.duobaan.model.dto.EmbeddingResponse;
import org.example.duobaan.util.OutboundUrlGuard;
import org.example.duobaan.util.UrlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

/**
 * 向量化服务：使用「向量化模型」独立配置（ApiProfileType.EMBEDDING 的
 * baseUrl + apiKey + model），调用 OpenAI 兼容的 /embeddings 接口把文本转成向量。
 * 不复用 LLM 配置：用户需要在「设置 → 向量化模型」Tab 中单独填写 Base URL / Key / 模型 ID。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ApiProfileService apiProfileService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public EmbeddingService(ApiProfileService apiProfileService,
                            RestClient externalRestClient,
                            ObjectMapper objectMapper) {
        this.apiProfileService = apiProfileService;
        this.restClient = externalRestClient;
        this.objectMapper = objectMapper;
    }

    /** 向量化是否就绪：已激活 EMBEDDING 配置且填了模型 ID */
    public boolean isReady() {
        return apiProfileService.getActivePlain(ApiProfileType.EMBEDDING).isPresent();
    }

    /** 是否已配置（与 isReady 同义，保留以兼容旧调用） */
    public boolean llmConfigured() {
        return isReady();
    }

    /**
     * 批量向量化。返回与输入等长的 float[]，条目顺序与 texts 一致；
     * 任一条失败则对应位置为 null；整体异常返回空列表，调用方据此降级跳过 RAG。
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        ApiProfile emb = apiProfileService.getActivePlain(ApiProfileType.EMBEDDING).orElse(null);
        if (emb == null || emb.getModel() == null || emb.getModel().isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", emb.getModel());
            body.put("input", texts);
            String base = UrlSanitizer.sanitizeBaseUrl(emb.getBaseUrl());
            if (base == null || base.isBlank()) {
                log.warn("向量化 baseUrl 为空或非法，跳过 RAG：{}", emb.getBaseUrl());
                return List.of();
            }
            String url = base + "/embeddings";
            OutboundUrlGuard.verify(url);   // 出网地址守卫：阻断内网/环回/元数据地址
            EmbeddingResponse resp = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + emb.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(EmbeddingResponse.class);
            if (resp == null || resp.data() == null) {
                return List.of();
            }
            List<float[]> out = new ArrayList<>(texts.size());
            for (EmbeddingResponse.EmbeddingData d : resp.data()) {
                if (d == null || d.embedding() == null) {
                    out.add(null);
                    continue;
                }
                float[] v = new float[d.embedding().size()];
                int i = 0;
                for (Double x : d.embedding()) {
                    v[i++] = (x == null ? 0f : x.floatValue());
                }
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            // 向量化失败不阻断对话，跳过 RAG 即可（但需记录原因，避免静默失效）
            log.warn("向量化失败，跳过 RAG：{}", e.getMessage());
            return List.of();
        }
    }

    /** 单条向量化，失败返回 null */
    public float[] embedOne(String text) {
        List<float[]> r = embed(List.of(text));
        return (r.isEmpty() || r.get(0) == null) ? null : r.get(0);
    }

    /** 把向量序列化为 JSON 字符串（存库用），失败返回 null */
    public String toJson(float[] vector) {
        if (vector == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 JSON 字符串反序列化向量，失败返回 null */
    public float[] fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            return null;
        }
    }
}
