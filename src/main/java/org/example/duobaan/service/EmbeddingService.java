package org.example.duobaan.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.example.duobaan.model.ApiProfile;
import org.example.duobaan.model.ApiProfileType;
import org.example.duobaan.model.dto.EmbeddingResponse;
import org.example.duobaan.util.OutboundUrlGuard;
import org.example.duobaan.util.UrlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 向量化服务：使用「向量化模型」独立配置（ApiProfileType.EMBEDDING 的
 * baseUrl + apiKey + model），调用 OpenAI 兼容的 /embeddings 接口把文本转成向量。
 * 不复用 LLM 配置：用户需要在「设置 → 向量化模型」Tab 中单独填写 Base URL / Key / 模型 ID。
 *
 * <p>关于「静默失效」：向量化失败不能影响对话主链路，所以 {@link #embed(List)} 失败时
 * 仍然返回空列表/带 null 的等长列表；但失败原因会记录在 {@link #lastFailure()} 中，
 * 由状态接口、检索解释接口与文档入库接口翻译成用户可读的说明，
 * 避免再出现「额度耗尽 → 检索永远为空 → 用户以为知识库没内容」这种沉默故障。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** 额度类失败日志的最小间隔：额度耗尽时每次对话都会失败，不节流会刷屏 */
    private static final long QUOTA_WARN_INTERVAL_MS = 60_000L;
    /** 错误响应体参与判定的最大长度（只用于识别额度类关键字，不落库、不回传前端） */
    private static final int BODY_SNIPPET_MAX = 2_000;

    private final ApiProfileService apiProfileService;
    private final RestClient restClient;

    /** 最近一次失败（用于把「静默失效」翻译成用户可读原因） */
    private final AtomicReference<Failure> lastFailure = new AtomicReference<>();
    /** 额度类告警的节流时间戳（毫秒） */
    private final AtomicLong lastQuotaWarnAt = new AtomicLong(0L);

    public EmbeddingService(ApiProfileService apiProfileService,
                            RestClient externalRestClient) {
        this.apiProfileService = apiProfileService;
        this.restClient = externalRestClient;
    }

    /** 失败类别：只用于日志、状态展示与测试断言，不参与业务分支 */
    public enum FailureKind {
        /** 没有可用的 EMBEDDING 配置（或模型 ID 为空） */
        NOT_CONFIGURED,
        /** Base URL 为空 / 非法 / 指向内网等被出网守卫拦截 */
        OUTBOUND_BLOCKED,
        /** 服务商返回额度受限（免费额度用尽、余额不足等） */
        QUOTA_EXHAUSTED,
        /** 401：Key 无效或被拒绝 */
        AUTH_FAILED,
        /** 403：Key 无权限 */
        FORBIDDEN,
        /** 429：被限流 */
        RATE_LIMITED,
        /** 其它 4xx（模型 ID / Base URL 不匹配等） */
        BAD_REQUEST,
        /** 5xx：上游服务异常 */
        UPSTREAM_UNAVAILABLE,
        /** 连接失败 / 超时 / IO 异常 */
        NETWORK_ERROR,
        /** 上游返回空 data */
        EMPTY_RESULT,
        /** 返回条数与请求不一致（缺失位置按未向量化处理） */
        PARTIAL_RESULT,
        /** 其它未知异常 */
        UNKNOWN
    }

    /** 一次失败的可展示原因（不含 Key、请求体、厂商原文） */
    public record Failure(FailureKind kind, String message, Instant at) { }

    /** 状态码 + 响应体 → 失败类别与可读文案（包级可见，便于不联网单测） */
    record Mapping(FailureKind kind, String message) { }

    /** 向量化是否就绪：已激活 EMBEDDING 配置且填了模型 ID */
    public boolean isReady() {
        return apiProfileService.getActivePlain(ApiProfileType.EMBEDDING)
                .map(p -> p.getModel() != null && !p.getModel().isBlank())
                .orElse(false);
    }

    /** 最近一次失败（成功一次后自动清空） */
    public Optional<Failure> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    /**
     * 「为什么不可用」的人话后缀：状态页 / 检索解释 / 写入结果共用。
     * 没有失败记录时给一条通用排查提示，绝不编造原因。
     */
    public String failureSuffix() {
        Failure f = lastFailure.get();
        if (f == null) {
            return "未记录到具体失败原因，请检查向量化配置、额度与网络。";
        }
        return "最近一次失败：" + f.message();
    }

    /**
     * 批量向量化。返回与输入等长的 float[]，条目顺序与 texts 一致；
     * 某条失败时对应位置为 null；整体异常返回空列表（调用方据此降级跳过 RAG）。
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        ApiProfile emb = apiProfileService.getActivePlain(ApiProfileType.EMBEDDING).orElse(null);
        if (emb == null) {
            record(FailureKind.NOT_CONFIGURED, "未配置可用的向量化模型（设置 → 向量化模型）。");
            return List.of();
        }
        if (emb.getModel() == null || emb.getModel().isBlank()) {
            record(FailureKind.NOT_CONFIGURED, "向量化模型 ID 为空（设置 → 向量化模型）。");
            return List.of();
        }
        String base = UrlSanitizer.sanitizeBaseUrl(emb.getBaseUrl());
        if (base == null || base.isBlank()) {
            record(FailureKind.OUTBOUND_BLOCKED, "向量化 Base URL 为空或格式非法。");
            return List.of();
        }
        String url = base + "/embeddings";
        try {
            OutboundUrlGuard.verify(url);   // 出网地址守卫：阻断内网/环回/元数据地址
        } catch (IllegalArgumentException e) {
            record(FailureKind.OUTBOUND_BLOCKED, "向量化 Base URL 指向了禁止访问的地址（内网/环回/非法），请改为公网地址。");
            return List.of();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", emb.getModel());
            body.put("input", texts);
            EmbeddingResponse resp = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + emb.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(EmbeddingResponse.class);
            return toVectors(resp, texts.size());
        } catch (RestClientResponseException e) {
            Mapping m = classifyHttpStatus(e.getStatusCode().value(), e.getResponseBodyAsString());
            record(m.kind(), m.message(), e);
            return List.of();
        } catch (ResourceAccessException e) {
            Mapping m = classifyThrowable(e);
            record(m.kind(), m.message(), e);
            return List.of();
        } catch (Exception e) {
            Mapping m = classifyThrowable(e);
            record(m.kind(), m.message(), e);
            return List.of();
        }
    }

    /** 单条向量化，失败返回 null */
    public float[] embedOne(String text) {
        List<float[]> r = embed(List.of(text));
        return (r.isEmpty() || r.get(0) == null) ? null : r.get(0);
    }

    // ============================ 内部实现 ============================

    /**
     * 把响应按 data[].index 归位；index 缺失时退回数组顺序。
     * 条数不足时用 null 占位并由上层计数，保证「返回等长列表」这一契约。
     */
    private List<float[]> toVectors(EmbeddingResponse resp, int expected) {
        if (resp == null || resp.data() == null || resp.data().isEmpty()) {
            record(FailureKind.EMPTY_RESULT, "向量化服务返回空结果（data 为空）。");
            return List.of();
        }
        List<float[]> out = new ArrayList<>(Collections.nCopies(expected, (float[]) null));
        int placed = 0;
        for (int i = 0; i < resp.data().size() && i < expected; i++) {
            EmbeddingResponse.EmbeddingData d = resp.data().get(i);
            if (d == null || d.embedding() == null || d.embedding().isEmpty()) {
                continue;
            }
            int target = (d.index() != null && d.index() >= 0 && d.index() < expected) ? d.index() : i;
            if (out.get(target) != null) {
                // 同一位置重复返回：保留第一条，避免后到的覆盖先到的（服务商异常时更可预期）
                continue;
            }
            float[] v = new float[d.embedding().size()];
            for (int k = 0; k < v.length; k++) {
                Double x = d.embedding().get(k);
                v[k] = x == null ? 0f : x.floatValue();
            }
            out.set(target, v);
            placed++;
        }
        if (placed < expected) {
            record(FailureKind.PARTIAL_RESULT,
                    "向量化返回 " + placed + "/" + expected + " 条有效向量，缺失位置已按「未向量化」处理。");
        } else {
            lastFailure.set(null);
        }
        return out;
    }

    /** 记录并（按类别）输出失败日志；额度类做 60s 节流 */
    private void record(FailureKind kind, String message) {
        record(kind, message, null);
    }

    private void record(FailureKind kind, String message, Throwable cause) {
        lastFailure.set(new Failure(kind, message, Instant.now()));
        if (kind == FailureKind.NOT_CONFIGURED) {
            // 「没配置」是正常的初始状态，不是异常，降级为 debug 避免日志噪音
            log.debug("向量化不可用：{}", message);
            return;
        }
        if (kind == FailureKind.QUOTA_EXHAUSTED) {
            long now = System.currentTimeMillis();
            long last = lastQuotaWarnAt.get();
            if (now - last >= QUOTA_WARN_INTERVAL_MS && lastQuotaWarnAt.compareAndSet(last, now)) {
                log.warn("向量化额度类失败（同类告警 60 秒内只记一次）：{}", message);
            }
            return;
        }
        log.warn("向量化失败[{}]：{}", kind, message, cause);
    }

    /** HTTP 错误 → 类别 + 可读文案（不包含厂商原始报文） */
    static Mapping classifyHttpStatus(int status, String body) {
        if (looksLikeQuota(body)) {
            return new Mapping(FailureKind.QUOTA_EXHAUSTED,
                    "向量化额度已用尽（服务商返回额度受限），请到服务商控制台开通/充值后点「重建向量」。");
        }
        return switch (status) {
            case 401 -> new Mapping(FailureKind.AUTH_FAILED,
                    "向量化 API Key 无效或被拒绝（401），请到「设置 → 向量化模型」重新填写。");
            case 403 -> new Mapping(FailureKind.FORBIDDEN,
                    "向量化 API Key 无权限（403），请确认 Key 与模型权限。");
            case 429 -> new Mapping(FailureKind.RATE_LIMITED,
                    "向量化请求被服务商限流（429），请稍后重试。");
            case 400, 422 -> new Mapping(FailureKind.BAD_REQUEST,
                    "向量化请求被服务商拒绝（" + status + "），请检查模型 ID / Base URL 是否与该服务商匹配。");
            default -> status >= 500
                    ? new Mapping(FailureKind.UPSTREAM_UNAVAILABLE,
                            "向量化服务暂时不可用（" + status + "），请稍后重试。")
                    : new Mapping(FailureKind.UNKNOWN, "向量化请求失败（HTTP " + status + "）。");
        };
    }

    /** 网络/其它异常 → 类别 + 可读文案 */
    static Mapping classifyThrowable(Throwable t) {
        if (t == null) {
            return new Mapping(FailureKind.UNKNOWN, "向量化失败（未知原因）。");
        }
        Throwable cause = t;
        for (int depth = 0; depth < 4 && cause != null; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return new Mapping(FailureKind.NETWORK_ERROR, "向量化请求超时（网络或服务商响应过慢），请稍后重试。");
            }
            if (cause instanceof ConnectException) {
                return new Mapping(FailureKind.NETWORK_ERROR, "无法连接向量化服务，请检查 Base URL 与本机网络。");
            }
            if (cause instanceof IOException) {
                return new Mapping(FailureKind.NETWORK_ERROR, "向量化请求网络异常，请检查 Base URL 与本机网络。");
            }
        }
        return new Mapping(FailureKind.UNKNOWN, "向量化失败（未知错误），详见服务端日志。");
    }

    /** 响应体是否像「额度受限」（不同服务商措辞不同，关键字兜底） */
    static boolean looksLikeQuota(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String s = body.length() > BODY_SNIPPET_MAX ? body.substring(0, BODY_SNIPPET_MAX) : body;
        s = s.toLowerCase(Locale.ROOT);
        return s.contains("quota")
                || s.contains("freetier")
                || s.contains("free_tier")
                || s.contains("insufficient")
                || s.contains("billing")
                || s.contains("insufficient_quota")
                || s.contains("credit")
                || s.contains("额度")
                || s.contains("余额不足");
    }
}
