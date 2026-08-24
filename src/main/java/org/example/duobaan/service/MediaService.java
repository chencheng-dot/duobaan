package org.example.duobaan.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.ApiProfile;
import org.example.duobaan.model.ApiProfileType;
import org.example.duobaan.model.dto.MediaResponse;
import org.example.duobaan.model.dto.MediaResponse.MediaItem;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 多模态调用网关：文生图 / 文生语音(TTS) / 语音转写(ASR) / 文生视频。
 *
 * 调用约定：
 *   - 4 种模态各有独立的 ApiProfile.active，读取时分别按 IMAGE/AUDIO/VIDEO 取
 *   - baseUrl 统一用 /v1 根（经验教训：不把具体 endpoint 写进预设 baseUrl，避免 404）
 *   - 未配置 active profile 时，返回 MediaResponse.error(...)（中文友好提示，带下一步操作指引）
 *   - HTTP 401 → "API Key 无效/未授权"；404 → "模型ID或厂商地址不匹配"；其它 → 打印 HTTP 状态
 */
@Service
public class MediaService {

    /** 各模态超时建议秒数（可在 ApiProfile.timeoutSeconds 覆盖） */
    private static final int DEFAULT_TIMEOUT_IMG   = 60;   // 文生图通常 5-30s，给 60 留余量
    private static final int DEFAULT_TIMEOUT_AUDIO = 30;   // TTS / Whisper 转写短音频很快
    private static final int DEFAULT_TIMEOUT_VIDEO = 180;  // 视频生成慢，默认 3 分钟

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ApiProfileService apiProfileService;
    private final DuobaanProperties props;

    public MediaService(RestClient externalRestClient, HttpClient httpClient, ObjectMapper objectMapper,
                        ApiProfileService apiProfileService, DuobaanProperties props) {
        this.restClient = externalRestClient;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiProfileService = apiProfileService;
        this.props = props;
    }

    // ================================ IMAGE ================================

    /**
     * 文生图：POST /images/generations
     * @param prompt 必填
     * @param size   默认 1024x1024；常见可选 1024x1792 / 1792x1024
     * @param n      默认 1（DALL·E 3 仅支持 1）
     * @param style  vivid/natural（DALL·E 3）；传空则让厂商按默认
     * @param quality standard/hd；传空走默认
     */
    public MediaResponse generateImage(String prompt, String size, Integer n, String style, String quality) {
        if (prompt == null || prompt.isBlank()) return MediaResponse.error("IMAGE", "请输入要生成图片的提示词");
        Optional<ApiProfile> opt = apiProfileService.getActivePlain(ApiProfileType.IMAGE);
        if (opt.isEmpty() || !StringUtils.hasText(opt.get().getApiKey())) {
            return MediaResponse.error("IMAGE",
                    "⚠️ 未配置「图片模型」，请先到「设置 → 图片模型」选厂商填 Key 并点击「使用」。");
        }
        ApiProfile p = opt.get();
        int timeout = p.getTimeoutSeconds() != null && p.getTimeoutSeconds() > 0
                ? p.getTimeoutSeconds() : DEFAULT_TIMEOUT_IMG;
        try {
            String endpoint = ensureTrailing(p.getBaseUrl()) + "images/generations";
            String model = orModel(p.getModel(), "dall-e-3");

            String body = objectMapper.writeValueAsString(new ImageReq(
                    model, prompt, (n == null || n < 1) ? 1 : n,
                    StringUtils.hasText(size) ? size : "1024x1024",
                    "url", StringUtils.hasText(quality) ? quality : null,
                    StringUtils.hasText(style) ? style : null));

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + p.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return httpFail("IMAGE", resp.statusCode(), resp.body());

            JsonNode root = objectMapper.readTree(resp.body());
            List<MediaItem> items = new ArrayList<>();
            for (JsonNode d : root.path("data")) {
                items.add(new MediaItem(d.path("url").asText(null),
                        d.path("b64_json").asText(null),
                        d.path("revised_prompt").asText(null)));
            }
            if (items.isEmpty()) return MediaResponse.error("IMAGE", "模型未返回图片数据");
            return MediaResponse.image(items);
        } catch (Exception e) {
            return MediaResponse.error("IMAGE", friendlyErr("生图", e));
        }
    }

    // ================================ AUDIO TTS ================================

    /**
     * 文生语音：POST /audio/speech → 二进制 MP3 返回
     * @param input 必填，最长一般 4096 字符
     * @param voice alloy/echo/fable/onyx/nova/shimmer (OpenAI 标准)；为空默认 alloy
     * @param format mp3 / opus / aac / flac；默认 mp3
     * @param speed  0.25 ~ 4.0；默认 1.0
     */
    public MediaResponse generateSpeech(String input, String voice, String format, Double speed) {
        if (input == null || input.isBlank()) return MediaResponse.error("AUDIO_TTS", "请输入要朗读的文本");
        Optional<ApiProfile> opt = apiProfileService.getActivePlain(ApiProfileType.AUDIO);
        if (opt.isEmpty() || !StringUtils.hasText(opt.get().getApiKey())) {
            return MediaResponse.error("AUDIO_TTS",
                    "⚠️ 未配置「语音模型」，请先到「设置 → 语音模型」选厂商填 Key 并点击「使用」。");
        }
        ApiProfile p = opt.get();
        int timeout = p.getTimeoutSeconds() != null && p.getTimeoutSeconds() > 0
                ? p.getTimeoutSeconds() : DEFAULT_TIMEOUT_AUDIO;
        try {
            String endpoint = ensureTrailing(p.getBaseUrl()) + "audio/speech";
            String model = orModel(p.getModel(), "tts-1");
            String body = objectMapper.writeValueAsString(new TtsReq(model, input,
                    StringUtils.hasText(voice) ? voice : "alloy",
                    StringUtils.hasText(format) ? format : "mp3",
                    speed != null ? speed : 1.0));

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + p.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                String textResp = new String(resp.body(), StandardCharsets.UTF_8);
                return httpFail("AUDIO_TTS", resp.statusCode(), textResp);
            }
            byte[] bytes = resp.body();
            String mime = mimeForAudio(StringUtils.hasText(format) ? format : "mp3");
            String filename = "speech." + (StringUtils.hasText(format) ? format : "mp3");
            return MediaResponse.tts(bytes, mime, filename);
        } catch (Exception e) {
            return MediaResponse.error("AUDIO_TTS", friendlyErr("语音合成", e));
        }
    }

    // ================================ AUDIO ASR ================================

    /**
     * 语音转写：multipart POST /audio/transcriptions → 返回文本
     * @param file 上传的音频文件（mp3/wav/m4a/webm 等）
     */
    public MediaResponse transcribeAudio(MultipartFile file) {
        if (file == null || file.isEmpty()) return MediaResponse.error("AUDIO_ASR", "请先选择要转写的音频文件");
        Optional<ApiProfile> opt = apiProfileService.getActivePlain(ApiProfileType.AUDIO);
        if (opt.isEmpty() || !StringUtils.hasText(opt.get().getApiKey())) {
            return MediaResponse.error("AUDIO_ASR",
                    "⚠️ 未配置「语音模型」，请先到「设置 → 语音模型」填 Key（ASR 使用 whisper-1 系列模型）。");
        }
        ApiProfile p = opt.get();
        int timeout = p.getTimeoutSeconds() != null && p.getTimeoutSeconds() > 0
                ? p.getTimeoutSeconds() : DEFAULT_TIMEOUT_AUDIO;
        try {
            String endpoint = ensureTrailing(p.getBaseUrl()) + "audio/transcriptions";
            // 直接用 Spring RestClient 做 multipart（比 HttpClient 手写边界更简洁）
            var builder = new org.springframework.http.client.MultipartBodyBuilder();
            builder.part("file", file.getResource());
            builder.part("model", orAsrModel(p.getModel()));
            builder.part("response_format", "json");
            String text = restClient.post().uri(endpoint)
                    .header("Authorization", "Bearer " + p.getApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
                    .retrieve()
                    .body(String.class);
            // 响应体可能就是纯 JSON {"text": "..."}
            JsonNode node = objectMapper.readTree(text);
            String t = node.path("text").asText("").trim();
            if (!StringUtils.hasText(t)) t = text.trim();
            return MediaResponse.asr(t);
        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            return httpFail("AUDIO_ASR", ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (Exception e) {
            return MediaResponse.error("AUDIO_ASR", friendlyErr("语音转写", e));
        }
    }

    // ================================ VIDEO ================================

    /**
     * 文生视频：POST /videos/generations。
     * 注：多数视频厂商是异步「提交→轮询」模式，这里第一版实现「阻塞调用」：
     *     若响应直接含 data[].url 则立即返回；否则把原始 status/error 字段透传，前端可提示等待。
     * @param seconds  期望时长（s），一般 5/10；默认 5
     * @param ratio    16:9 / 9:16 / 1:1；默认 16:9
     */
    public MediaResponse generateVideo(String prompt, Integer seconds, String ratio) {
        if (prompt == null || prompt.isBlank()) return MediaResponse.error("VIDEO", "请输入要生成视频的提示词");
        Optional<ApiProfile> opt = apiProfileService.getActivePlain(ApiProfileType.VIDEO);
        if (opt.isEmpty() || !StringUtils.hasText(opt.get().getApiKey())) {
            return MediaResponse.error("VIDEO",
                    "⚠️ 未配置「视频模型」，请先到「设置 → 视频模型」选厂商（如 Seedance/可灵）填 Key 并点击「使用」。");
        }
        ApiProfile p = opt.get();
        int timeout = p.getTimeoutSeconds() != null && p.getTimeoutSeconds() > 0
                ? p.getTimeoutSeconds() : DEFAULT_TIMEOUT_VIDEO;
        try {
            String endpoint = ensureTrailing(p.getBaseUrl()) + "videos/generations";
            String model = orModel(p.getModel(), "seedance-1-0-pro");
            String body = objectMapper.writeValueAsString(new VideoReq(model, prompt,
                    seconds == null || seconds <= 0 ? 5 : seconds,
                    StringUtils.hasText(ratio) ? ratio : "16:9"));

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + p.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return httpFail("VIDEO", resp.statusCode(), resp.body());

            JsonNode root = objectMapper.readTree(resp.body());
            List<MediaItem> items = new ArrayList<>();
            for (JsonNode d : root.path("data")) {
                String url = d.path("url").asText(null);
                String b64 = d.path("b64_json").asText(null);
                if (StringUtils.hasText(url) || StringUtils.hasText(b64)) {
                    items.add(new MediaItem(url, b64, null));
                }
            }
            // 判断异步还是同步：若 items 为空但有 status，按异步 pending 状态返回
            String status = root.path("status").asText(null);
            if (items.isEmpty()) {
                if ("pending".equalsIgnoreCase(status) || "processing".equalsIgnoreCase(status)) {
                    return MediaResponse.video(List.of(), "pending");
                }
                String err = root.path("error").path("message").asText(null);
                return MediaResponse.error("VIDEO",
                        StringUtils.hasText(err) ? "模型返回错误：" + err : "模型未返回视频地址（status=" + status + "），请稍后重试");
            }
            return MediaResponse.video(items, StringUtils.hasText(status) ? status : "succeeded");
        } catch (Exception e) {
            return MediaResponse.error("VIDEO", friendlyErr("视频生成", e));
        }
    }

    // ===================================== 内部辅助 =====================================

    private static String ensureTrailing(String base) {
        if (base == null || base.isBlank()) return "";
        return base.endsWith("/") ? base : base + "/";
    }

    private static String orModel(String m, String fallback) {
        return StringUtils.hasText(m) ? m : fallback;
    }

    /** ASR 模型 ID 约定：优先用户写的 model，否则用 whisper-1 行业通用 ID */
    private static String orAsrModel(String m) {
        if (StringUtils.hasText(m) && !m.toLowerCase().startsWith("tts")) return m;
        return "whisper-1";
    }

    private static String mimeForAudio(String format) {
        return switch (format.toLowerCase()) {
            case "opus" -> "audio/opus";
            case "aac"  -> "audio/aac";
            case "flac" -> "audio/flac";
            case "wav"  -> "audio/wav";
            default     -> "audio/mpeg"; // mp3
        };
    }

    private MediaResponse httpFail(String kind, int code, String body) {
        String msg;
        try {
            JsonNode err = objectMapper.readTree(body).path("error");
            String m = err.path("message").asText(null);
            if (!StringUtils.hasText(m)) m = err.isTextual() ? err.asText() : null;
            msg = StringUtils.hasText(m) ? m : body;
            if (msg.length() > 280) msg = msg.substring(0, 280) + "…";
        } catch (Exception ignore) { msg = body; }
        String friendly = switch (code) {
            case 401 -> "❌ API Key 无效或未授权，请检查 Key 是否填写正确（HTTP 401）。";
            case 403 -> "❌ 账户被拒访问该模型，或配额耗尽（HTTP 403）：" + msg;
            case 404 -> "❌ 模型 ID 或厂商地址有误，请核对预设模型或自定义 baseUrl（HTTP 404）。";
            case 429 -> "❌ 请求过于频繁或额度不足，稍后再试（HTTP 429）：" + msg;
            default -> "❌ 接口调用失败（HTTP " + code + "）：" + msg;
        };
        return MediaResponse.error(kind, friendly);
    }

    private static String friendlyErr(String action, Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("timed out") || m.contains("timeout")) {
            return "⏱️ " + action + "请求超时，请稍后重试或在配置里调大 timeoutSeconds。";
        }
        if (m.contains("UnknownHost")) {
            return "🌐 " + action + "失败：无法解析厂商域名，请检查 baseUrl 和网络连接。";
        }
        if (m.contains("connect")) {
            return "🚫 " + action + "失败：无法连接到厂商接口，请检查 baseUrl 是否正确。";
        }
        return "❌ " + action + "异常：" + (m.isEmpty() ? e.getClass().getSimpleName() : m);
    }

    // =========================== 请求体 records（局部用，避免外部污染包）===========================
    private record ImageReq(String model, String prompt, int n, String size, String response_format,
                            String quality, String style) {}
    private record TtsReq(String model, String input, String voice, String response_format, double speed) {}
    private record VideoReq(String model, String prompt, int durationSeconds, String size) {}
}
