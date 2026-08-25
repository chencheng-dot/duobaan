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
import org.example.duobaan.util.UrlSanitizer;
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

    /**
     * Dashscope（阿里通义/万相/Sambert/CosyVoice/Paraformer）原生接口根。
     * 必须用原生根而非 compatible-mode/v1：兼容模式只开放 chat/completions + embeddings，
     * 图/TTS/ASR/视频 4 模态都必须走 https://dashscope.aliyuncs.com/api/v1/services/aigc/* 系列。
     */
    private static final String DASHSCOPE_ROOT = "https://dashscope.aliyuncs.com/";

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
        final ProviderVendor vendor = vendorOf(p);
        try {
            // —— Dashscope（万相）原生分支：compatible-mode/v1 不支持 /images/generations，必须用原生 aigc 路径 ——
            if (vendor == ProviderVendor.DASHSCOPE) {
                String model = orModel(p.getModel(), "wanx2.1-t2i-turbo");
                String ml = model.toLowerCase();
                // —— 千问图片系列 (qwen-image-*)：不同的原生 endpoint ——
                // 千问图片走 /api/v1/services/aigc/image-generation/generation，与万相 /text2image/image-synthesis 不同
                boolean isQwenImage = ml.startsWith("qwen-image");
                if (isQwenImage) {
                    // 千问图片原生接口
                    final String endpoint = DASHSCOPE_ROOT + "api/v1/services/aigc/image-generation/generation";
                    String sizeParam = dashscopeImageSize(StringUtils.hasText(size) ? size : "1024x1024");
                    int nVal = (n == null || n < 1) ? 1 : Math.min(n, 4);
                    var node = objectMapper.createObjectNode();
                    node.put("model", model);
                    var input = node.putObject("input");
                    input.put("prompt", prompt);
                    var params = node.putObject("parameters");
                    params.put("size", sizeParam);
                    params.put("n", nVal);
                    String body = objectMapper.writeValueAsString(node);
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(timeout))
                            .header("Authorization", "Bearer " + p.getApiKey())
                            .header("X-DashScope-Async", "enable")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (resp.statusCode() != 200) return httpFail("IMAGE", resp.statusCode(), resp.body(), vendor);
                    JsonNode root = objectMapper.readTree(resp.body());
                    String taskId = root.path("output").path("task_id").asText(null);
                    List<MediaItem> items = collectDashscopeResults(root.path("output").path("results"));
                    if (items.isEmpty() && StringUtils.hasText(taskId)) {
                        return MediaResponse.image(List.of(), "pending",
                                "千问图片任务已提交（task_id=" + taskId + "），稍后刷新即可看到结果。");
                    }
                    if (items.isEmpty()) {
                        String err = root.path("message").asText(null);
                        return MediaResponse.error("IMAGE",
                                StringUtils.hasText(err) ? "模型返回错误：" + err : "千问图片未返回图片地址");
                    }
                    return MediaResponse.image(items);
                }
                // —— 防呆：把视频模型（t2v=text-to-video）填到「图片模型」配置里的情况 ——
                // 例如 wan2.7-t2v-2026-06-12 / wan2.1-t2v-turbo 等：这些只能走视频合成接口，文生图接口会 404/url error
                if (ml.contains("-t2v-") || ml.startsWith("wan-t2v") || ml.startsWith("wan2.") && ml.contains("t2v")) {
                    return MediaResponse.error("IMAGE",
                            "❌ 检测到模型 ID `" + model + "` 是「文生视频」模型（t2v），不能用于图片生成。" +
                                    "请到「设置 → 图片模型」把模型 ID 换成图片系列：推荐 wanx2.1-t2i-turbo（万相文生图旗舰版），" +
                                    "或 wanx1.5-t2i-plus，如需视频请切换到「视频」面板使用「视频模型」配置。");
                }
                // 百炼官方原生接口：POST https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis
                // 注：/image-generation/generation 是千问 Qwen-Image 系列接口，与万相 wanx / wan t2i 不兼容会报 url error
                final String endpoint = DASHSCOPE_ROOT + "api/v1/services/aigc/text2image/image-synthesis";
                String sizeParam = dashscopeImageSize(StringUtils.hasText(size) ? size : "1024x1024");
                int nVal = (n == null || n < 1) ? 1 : Math.min(n, 4);
                var node = objectMapper.createObjectNode();
                node.put("model", model);
                var input = node.putObject("input");
                input.put("prompt", prompt);
                var params = node.putObject("parameters");
                params.put("size", sizeParam);
                params.put("n", nVal);
                if (StringUtils.hasText(style)) params.put("style", style);
                String body = objectMapper.writeValueAsString(node);
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(timeout))
                        .header("Authorization", "Bearer " + p.getApiKey())
                        .header("X-DashScope-Async", "enable")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) return httpFail("IMAGE", resp.statusCode(), resp.body(), vendor);
                JsonNode root = objectMapper.readTree(resp.body());
                String taskId = root.path("output").path("task_id").asText(null);
                String taskStatus = root.path("output").path("task_status").asText(null);
                // 万相原生是异步：提交只给 task_id；如果任务立刻有 results（极少），直接抽 url
                List<MediaItem> items = collectDashscopeResults(root.path("output").path("results"));
                if (items.isEmpty() && StringUtils.hasText(taskId)) {
                    return MediaResponse.image(List.of(), "pending",
                            "万相生图任务已提交（task_id=" + taskId + ", status=" + or(taskStatus, "PENDING") + "），稍后刷新即可看到结果。");
                }
                if (items.isEmpty()) {
                    String err = root.path("message").asText(null);
                    return MediaResponse.error("IMAGE",
                            StringUtils.hasText(err) ? "模型返回错误：" + err : "万相未返回图片地址（status=" + taskStatus + "）");
                }
                return MediaResponse.image(items);
            }

            final String action = "images/generations";
            String endpoint = ensureTrailing(p.getBaseUrl()) + action;
            endpoint = dedupSuffix(endpoint, "/" + action);
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
            if (resp.statusCode() != 200) return httpFail("IMAGE", resp.statusCode(), resp.body(), vendor);

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
        final ProviderVendor vendor = vendorOf(p);
        try {
            // —— Dashscope TTS 按模型系列路由（endpoint/body 格式完全不同）——
            if (vendor == ProviderVendor.DASHSCOPE) {
                String model = orModel(p.getModel(), "qwen3-tts-flash");
                String ml = model.toLowerCase();
                // Sambert 仅支持 WebSocket（wss://.../api-ws/v1/inference），无 HTTP API
                if (ml.startsWith("sambert-")) {
                    return MediaResponse.error("AUDIO_TTS",
                            "ℹ️ Dashscope Sambert 系列模型仅提供 WebSocket 实时接口，" +
                                    "暂时无法通过 HTTP API 调用。请到「设置 → 语音模型」把模型 ID 换成支持 HTTP 的" +
                                    " **qwen3-tts-flash**（基础版）或 **qwen3-tts-instruct-flash**（支持指令控制）即可正常使用。");
                }
                // CosyVoice / Qwen-Audio-TTS 需要 workspace 专属域名（{WorkspaceId}.cn-beijing.maas.aliyuncs.com）
                if (ml.startsWith("cosyvoice") || ml.startsWith("qwen-audio")) {
                    return MediaResponse.error("AUDIO_TTS",
                            "ℹ️ " + model + " 系列需要配置 Workspace 专属域名才能通过 HTTP 调用。" +
                                    "请到 [百炼控制台](https://dashscope.console.aliyun.com/) 获取你的 Workspace ID，" +
                                    "然后在「设置 → 语音模型 → API Base URL」填入 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com`。" +
                                    "推荐直接使用 **qwen3-tts-flash**（兼容 dashscope.aliyuncs.com 通用域名）。");
                }
                // Qwen-TTS (qwen3-tts-*) 走 multimodal-generation/generation，请求体格式不同
                if (ml.startsWith("qwen3-tts")) {
                    return qwen3TtsCall(p, model, input, speed);
                }
                // 兜底：所有其他 Dashscope TTS 模型走 SpeechSynthesizer（通用 endpoint）
                String outFormat = dashscopeAudioFormat(StringUtils.hasText(format) ? format : "mp3");
                int sampleRate = 22050;
                String vol = StringUtils.hasText(voice) ? voice : null;
                double rateVal = speed == null ? 1.0 : speed;
                final String endpoint = DASHSCOPE_ROOT + "api/v1/services/audio/tts/SpeechSynthesizer";
                var node = objectMapper.createObjectNode();
                node.put("model", model);
                var inObj = node.putObject("input");
                inObj.put("text", input);
                inObj.put("format", outFormat);
                inObj.put("sample_rate", sampleRate);
                if (StringUtils.hasText(vol)) inObj.put("voice", vol);
                inObj.put("volume", 50);
                inObj.put("rate", rateVal);
                inObj.put("pitch", 1.0);
                String body = objectMapper.writeValueAsString(node);
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(timeout))
                        .header("Authorization", "Bearer " + p.getApiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) return httpFail("AUDIO_TTS", resp.statusCode(), resp.body(), vendor);
                return parseTtsResponse(resp.body(), outFormat, model);
            }

            final String action = "audio/speech";
            String endpoint = ensureTrailing(p.getBaseUrl()) + action;
            endpoint = dedupSuffix(endpoint, "/" + action);
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
                return httpFail("AUDIO_TTS", resp.statusCode(), textResp, vendor);
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
                    "⚠️ 未配置「语音模型」，请先到「设置 → 语音模型」填 Key（ASR 使用 whisper-1 或 paraformer-v2 系列模型）。");
        }
        ApiProfile p = opt.get();
        int timeout = p.getTimeoutSeconds() != null && p.getTimeoutSeconds() > 0
                ? p.getTimeoutSeconds() : DEFAULT_TIMEOUT_AUDIO;
        final ProviderVendor vendor = vendorOf(p);
        try {
            // —— Dashscope（Paraformer/SeACosformer）原生分支 ——
            if (vendor == ProviderVendor.DASHSCOPE) {
                final String endpoint = DASHSCOPE_ROOT + "api/v1/services/aigc/asr/transcription";
                String model = orModel(p.getModel(), "paraformer-v2");
                // Dashscope 原生 ASR：POST 统一 JSON {model, input:{file_url|file_stream}, parameters:{format, hotwords}}
                // 这里把本地音频先 POST 成 multipart with named parts，按 Dashscope HTTP 文档以 file_stream + model + parameters JSON multipart mixed 提交
                var mb = new org.springframework.http.client.MultipartBodyBuilder();
                mb.part("model", model);
                mb.part("file", file.getResource());
                var params = objectMapper.createObjectNode();
                String guessExt = guessFileExtension(file.getOriginalFilename());
                if (guessExt != null) params.put("format", guessExt.replace(".", ""));
                mb.part("parameters", objectMapper.writeValueAsString(params), org.springframework.http.MediaType.APPLICATION_JSON);
                try {
                    String text = restClient.post().uri(endpoint)
                            .header("Authorization", "Bearer " + p.getApiKey())
                            .header("X-DashScope-Async", "enable")
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(mb.build())
                            .retrieve()
                            .body(String.class);
                    JsonNode root = objectMapper.readTree(text);
                    StringBuilder sb = new StringBuilder();
                    JsonNode outs = root.path("output");
                    if (outs.has("sentences")) for (JsonNode s : outs.path("sentences")) sb.append(s.path("text").asText(""));
                    if (sb.isEmpty()) {
                        String single = outs.path("text").asText("");
                        if (single.isBlank()) single = root.path("message").asText("");
                        sb.append(single);
                    }
                    return MediaResponse.asr(sb.toString().trim());
                } catch (org.springframework.web.client.HttpStatusCodeException ex) {
                    return httpFail("AUDIO_ASR", ex.getStatusCode().value(), ex.getResponseBodyAsString(), vendor);
                }
            }

            final String action = "audio/transcriptions";
            String endpoint = ensureTrailing(p.getBaseUrl()) + action;
            endpoint = dedupSuffix(endpoint, "/" + action);
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
            return httpFail("AUDIO_ASR", ex.getStatusCode().value(), ex.getResponseBodyAsString(), vendor);
        } catch (Exception e) {
            return MediaResponse.error("AUDIO_ASR", friendlyErr("语音转写", e));
        }
    }

    // ================================ VIDEO ================================

    /**
     * 文生视频：按厂商分流：
     *   - Dashscope（万相 WANX_VIDEO）：原生 POST https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis
     *       —— body 格式不是 OpenAI 兼容，而是 Dashscope 专有的 {model, input:{prompt}, parameters:{...}}
     *       —— 必须带 header X-DashScope-Async: enable；404 常因用户误用 compatible-mode/v1/videos/generations 引起
     *   - VOLC 方舟 / 快手可灵 / 其他：OpenAI 兼容 POST {base}/videos/generations
     * @param seconds  期望时长（s），一般 5/10；默认 5
     * @param ratio    16:9 / 9:16 / 1:1；默认 16:9
     */
    public MediaResponse generateVideo(String prompt, Integer seconds, String ratio) {
        if (prompt == null || prompt.isBlank()) return MediaResponse.error("VIDEO", "请输入要生成视频的提示词");
        Optional<ApiProfile> opt = apiProfileService.getActivePlain(ApiProfileType.VIDEO);
        if (opt.isEmpty() || !StringUtils.hasText(opt.get().getApiKey())) {
            return MediaResponse.error("VIDEO",
                    "⚠️ 未配置「视频模型」，请先到「设置 → 视频模型」选厂商（如 Seedance/可灵/万相）填 Key 并点击「使用」。");
        }
        ApiProfile p = opt.get();
        int timeout = p.getTimeoutSeconds() != null && p.getTimeoutSeconds() > 0
                ? p.getTimeoutSeconds() : DEFAULT_TIMEOUT_VIDEO;
        final ProviderVendor vendor = vendorOf(p);
        try {
            // ========= 选 endpoint + body + headers（按厂商）=========
            String endpoint;
            String body;
            String authHeader = "Bearer " + p.getApiKey();
            String xDashScopeAsync = null;

            if (vendor == ProviderVendor.DASHSCOPE) {
                // 关键：Dashscope 原生视频接口！兼容模式 /videos/generations 在万相上经常未开放 → 404
                final String act = "api/v1/services/aigc/video-generation/video-synthesis";
                // baseUrl 可能被用户写成 compatible-mode 也可能写了原生前缀；这里强制用原生
                String base = ensureTrailing("https://dashscope.aliyuncs.com");
                endpoint = base + act;
                // Dashscope 请求体（{model, input:{prompt}, parameters:{size, duration_seconds}}）
                // size 字段映射：16:9→1280*720；9:16→720*1280；1:1→720*720
                String sizeParam = dashscopeVideoSize(StringUtils.hasText(ratio) ? ratio : "16:9");
                int dur = seconds == null || seconds <= 0 ? 5 : seconds;
                String model = orModel(p.getModel(), "wan2.1-t2v-turbo");
                var node = objectMapper.createObjectNode();
                node.put("model", model);
                var input = node.putObject("input");
                input.put("prompt", prompt);
                var params = node.putObject("parameters");
                params.put("size", sizeParam);
                params.put("duration_seconds", dur);
                body = objectMapper.writeValueAsString(node);
                xDashScopeAsync = "enable";
            } else {
                final String action = "videos/generations";
                endpoint = ensureTrailing(p.getBaseUrl()) + action;
                endpoint = dedupSuffix(endpoint, "/" + action);
                String model = orModel(p.getModel(), "seedance-1-0-pro");
                body = objectMapper.writeValueAsString(new VideoReq(model, prompt,
                        seconds == null || seconds <= 0 ? 5 : seconds,
                        StringUtils.hasText(ratio) ? ratio : "16:9"));
            }

            var rb = HttpRequest.newBuilder().uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json");
            if (xDashScopeAsync != null) rb.header("X-DashScope-Async", xDashScopeAsync);
            HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return httpFail("VIDEO", resp.statusCode(), resp.body());

            JsonNode root = objectMapper.readTree(resp.body());
            List<MediaItem> items = new ArrayList<>();
            // Dashscope 异步任务创建成功后字段：output.task_id, request_id, code, message, status_code；兼容格式 output.task_status
            String dashTaskId = root.path("output").path("task_id").asText(null);
            String dashStatus = root.path("output").path("task_status").asText(null);
            String dashRequestId = root.path("request_id").asText(null);

            for (JsonNode d : root.path("data")) {
                String url = d.path("url").asText(null);
                String b64 = d.path("b64_json").asText(null);
                if (StringUtils.hasText(url) || StringUtils.hasText(b64)) {
                    items.add(new MediaItem(url, b64, null));
                }
            }
            String status = root.path("status").asText(null);
            if (items.isEmpty()) {
                // Dashscope 异步提交成功：直接 pending，提示用户可点刷新
                if (StringUtils.hasText(dashTaskId) || StringUtils.hasText(dashStatus)) {
                    String msg = String.format("万相任务已提交（task_id=%s, task_status=%s），稍后可点击「刷新」按钮查询结果。%s",
                            dashTaskId == null ? "" : dashTaskId,
                            dashStatus == null ? "PENDING" : dashStatus,
                            StringUtils.hasText(dashRequestId) ? "(request_id=" + dashRequestId + ")" : "");
                    return MediaResponse.video(List.of(), "pending", msg);
                }
                if ("pending".equalsIgnoreCase(status) || "processing".equalsIgnoreCase(status)) {
                    return MediaResponse.video(List.of(), "pending");
                }
                String err = root.path("error").path("message").asText(null);
                if (!StringUtils.hasText(err)) err = root.path("message").asText(null);
                if (!StringUtils.hasText(err) && root.path("code").asText(null) != null) {
                    err = "code=" + root.path("code").asText() + " / message=" + root.path("message").asText("");
                }
                return MediaResponse.error("VIDEO",
                        StringUtils.hasText(err) ? "模型返回错误：" + err : "模型未返回视频地址（status=" + status + "），请稍后重试");
            }
            return MediaResponse.video(items, StringUtils.hasText(status) ? status : "succeeded");
        } catch (Exception e) {
            return MediaResponse.error("VIDEO", friendlyErr("视频生成", e));
        }
    }

    // ================================ Dashscope TTS helpers ================================

    /**
     * Qwen-TTS (qwen3-tts-*) 系列专用：endpoint 不同，请求体格式不同。
     * 文档：POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
     * Body: {model, input:{text, voice, language_type}}
     * 响应: output.audio.url → 直接返回音频 URL
     */
    private MediaResponse qwen3TtsCall(ApiProfile p, String model, String text, Double speed) {
        int timeout = p.getTimeoutSeconds() != null && p.getTimeoutSeconds() > 0
                ? p.getTimeoutSeconds() : DEFAULT_TIMEOUT_AUDIO;
        final String endpoint = DASHSCOPE_ROOT + "api/v1/services/aigc/multimodal-generation/generation";
        var node = objectMapper.createObjectNode();
        node.put("model", model);
        var inObj = node.putObject("input");
        inObj.put("text", text);
        inObj.put("voice", "Cherry"); // 默认女声 Cherry，用户可在未来前端选择
        inObj.put("language_type", "Chinese");
        String body = objectMapper.writeValueAsString(node);
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + p.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return httpFail("AUDIO_TTS", resp.statusCode(), resp.body(), ProviderVendor.DASHSCOPE);
            JsonNode root = objectMapper.readTree(resp.body());
            String audioUrl = root.path("output").path("audio").path("url").asText(null);
            if (!StringUtils.hasText(audioUrl)) {
                audioUrl = root.path("output").path("audio_url").asText(null);
            }
            if (StringUtils.hasText(audioUrl)) {
                byte[] bytes = httpClient.send(
                        HttpRequest.newBuilder().uri(URI.create(audioUrl)).timeout(Duration.ofSeconds(timeout)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body();
                return MediaResponse.tts(bytes, "audio/mpeg", "speech.mp3");
            }
            String err = root.path("message").asText(null);
            return MediaResponse.error("AUDIO_TTS",
                    StringUtils.hasText(err) ? "模型返回错误：" + err : "Qwen-TTS 未返回音频地址");
        } catch (Exception e) {
            return MediaResponse.error("AUDIO_TTS", friendlyErr("Qwen-TTS", e));
        }
    }

    /** 统一解析 Dashscope TTS 响应（SpeechSynthesizer 系列） */
    private MediaResponse parseTtsResponse(String body, String outFormat, String model) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String audioUrl = root.path("output").path("audio_url").asText(null);
            if (!StringUtils.hasText(audioUrl) && root.path("output").has("results")) {
                for (JsonNode r : root.path("output").path("results")) {
                    audioUrl = r.path("audio_url").asText(null);
                    if (StringUtils.hasText(audioUrl)) break;
                }
            }
            if (StringUtils.hasText(audioUrl)) {
                byte[] bytes = httpClient.send(
                        HttpRequest.newBuilder().uri(URI.create(audioUrl)).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body();
                return MediaResponse.tts(bytes, mimeForAudio(outFormat), "speech." + outFormat);
            }
            String taskId = root.path("output").path("task_id").asText(null);
            String taskStatus = root.path("output").path("task_status").asText(null);
            if (StringUtils.hasText(taskId)) {
                return MediaResponse.ttsPending(
                        "语音合成任务已提交（task_id=" + taskId + ", status=" + or(taskStatus, "PENDING") + "），几秒后请点击刷新获取音频。");
            }
            String err = root.path("message").asText(null);
            return MediaResponse.error("AUDIO_TTS",
                    StringUtils.hasText(err) ? "模型返回错误：" + err : "未返回音频地址");
        } catch (Exception e) {
            return MediaResponse.error("AUDIO_TTS", "解析 TTS 响应失败：" + e.getMessage());
        }
    }

    /** Dashscope 视频 size：按比例（16:9 / 9:16 / 1:1）映射到官方允许的像素尺寸 */
    private static String dashscopeVideoSize(String ratio) {
        return switch (ratio == null ? "" : ratio.trim()) {
            case "9:16" -> "720*1280";
            case "1:1"  -> "720*720";
            default     -> "1280*720";
        };
    }

    /**
     * Dashscope 万相生图 size：把 OpenAI 约定的 "WxH" 格式（1024x1024）转换成
     * 万相原生参数需要的 "W*H"；其余常见尺寸按文档支持直接透传。
     */
    private static String dashscopeImageSize(String size) {
        String s = size == null ? "1024x1024" : size.trim().toLowerCase();
        // 万相原生支持：1024*1024, 720*1280, 1280*720, 768*768, 512*512 …
        return s.replace("x", "*");
    }

    /** Dashscope TTS/ASR format：把 OpenAI 兼容名（mp3/wav/aac/flac/opus）对齐到原生支持值 */
    private static String dashscopeAudioFormat(String format) {
        String f = format == null ? "mp3" : format.trim().toLowerCase();
        return switch (f) {
            case "wav", "pcm", "aac", "flac", "opus" -> f;
            default -> "mp3";
        };
    }

    // ===================================== 内部辅助 =====================================

    /** 关键：先 sanitize 再拼尾部 / — 彻底搞定 Markdown 包络字符（用户截图里 > `https://…` 这种脏数据） */
    private static String ensureTrailing(String base) {
        String s = UrlSanitizer.sanitizeBaseUrl(base);
        if (s == null || s.isBlank()) return "";
        return s.endsWith("/") ? s : s + "/";
    }

    /** 兜底：如果 ensureTrailing 已经拼了 "/images/generations" 之类 action 路径但用户 baseUrl 本身就带 action，
     *  这里又 append 会重复。所以统一在 build 最终 endpoint 前做去重。 */
    private static String dedupSuffix(String endpoint, String action) {
        if (endpoint == null || endpoint.isEmpty() || action == null || action.isEmpty()) return endpoint;
        String bad1 = (action.startsWith("/") ? "" : "/") + action + action;
        String good = (action.startsWith("/") ? "" : "/") + action;
        // 把 "//images/generations" 这种 "尾 path + 重复" 直接换一个
        int idx;
        while ((idx = endpoint.toLowerCase().indexOf(good.toLowerCase() + good.toLowerCase())) >= 0) {
            endpoint = endpoint.substring(0, idx + good.length()) + endpoint.substring(idx + good.length() + good.length());
        }
        // 兼容：兼容模式 baseUrl 本身含 compatible-mode，不能动；只处理明显重复的 /xxx/xxx 同段
        return endpoint;
    }

    private static String orModel(String m, String fallback) {
        return StringUtils.hasText(m) ? m : fallback;
    }

    /** 通用空值兜底：s 为空则返回 fallback */
    private static String or(String s, String fallback) {
        return StringUtils.hasText(s) ? s : fallback;
    }

    /**
     * 从 Dashscope 原生异步响应的 output.results 数组抽取 MediaItem 列表。
     * 图结果元素格式：{url: "...", b64_image: "..."}；视频则是 {url: "..."}。
     */
    private static List<MediaResponse.MediaItem> collectDashscopeResults(JsonNode results) {
        List<MediaResponse.MediaItem> items = new ArrayList<>();
        if (results == null || !results.isArray()) return items;
        for (JsonNode r : results) {
            String url = r.path("url").asText(null);
            String b64 = r.path("b64_image").asText(null);
            if (!StringUtils.hasText(b64)) b64 = r.path("b64_json").asText(null);
            String rp = r.path("revised_prompt").asText(null);
            if (StringUtils.hasText(url) || StringUtils.hasText(b64)) {
                items.add(new MediaResponse.MediaItem(url, b64, rp));
            }
        }
        return items;
    }

    /** 取文件名后缀（包含点），例如 "speech.mp3" → ".mp3"；无法判断返回 null */
    private static String guessFileExtension(String filename) {
        if (!StringUtils.hasText(filename)) return null;
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) return null;
        return filename.substring(idx);
    }

    /**
     * 厂商识别（大小写不敏感 + 别名 + 模型ID前缀兜底）：用于 Dashscope 原生分支与 OpenAI 兼容分支分流。
     *
     *   DASHSCOPE → 阿里通义万相：provider 前缀 DASHSCOPE/WANXIANG/WANX_VIDEO/WANX/QIANWEN/TONGYI/通义
     *                        或 baseUrl 包含 dashscope.aliyuncs.com
     *                        或 模型ID 前缀 wanx/wan-t2i/wan-t2v/wan2.1/wan2.7/sambert/cosyvoice/qwen-audio/paraformer
     *   KUAISHOU   → 快手可灵 Kling
     *   VOLC / DOUBAO / SEEDANCE → 火山方舟（豆包/Seedance/Seedream）
     */
    private static ProviderVendor vendorOf(ApiProfile p) {
        String provider = p.getProvider() == null ? "" : p.getProvider().toUpperCase();
        String base = p.getBaseUrl() == null ? "" : p.getBaseUrl().toLowerCase();
        String model = p.getModel() == null ? "" : p.getModel().toLowerCase();
        // 第一层：provider / baseUrl 显式命中
        if (provider.contains("DASHSCOPE") || provider.contains("WANX") || provider.contains("QIANWEN")
                || provider.contains("TONGYI") || provider.contains("通义")
                || base.contains("dashscope.aliyuncs.com")) {
            return ProviderVendor.DASHSCOPE;
        }
        // 第二层（兜底）：模型ID前缀 — 用户选了「自定义」但填了 Dashscope 模型时依然能正确分流
        if (model.startsWith("wanx") || model.startsWith("wan-") || model.startsWith("wan2.")
                || model.startsWith("sambert-") || model.startsWith("cosyvoice")
                || model.startsWith("qwen-audio") || model.startsWith("qwen-image")
                || model.startsWith("qwen3-tts")
                || model.startsWith("paraformer") || model.startsWith("seacosformer")
                || model.startsWith("qwen-") && model.contains("tts")) {
            return ProviderVendor.DASHSCOPE;
        }
        if (provider.contains("KLING") || provider.contains("KUAISHOU") || provider.contains("可灵")
                || base.contains("klingai.com")) {
            return ProviderVendor.KUAISHOU_KLING;
        }
        if (provider.contains("VOLC") || provider.contains("DOUBAO") || provider.contains("ARK")
                || provider.contains("SEEDANCE") || provider.contains("SEEDREAM") || provider.contains("火山") || provider.contains("豆包")
                || base.contains("volces.com")) {
            return ProviderVendor.VOLC_ARK;
        }
        if (provider.contains("MINIMAX")) return ProviderVendor.MINIMAX;
        return ProviderVendor.OPENAI_COMPAT;
    }

    private enum ProviderVendor { OPENAI_COMPAT, DASHSCOPE, KUAISHOU_KLING, VOLC_ARK, MINIMAX }

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
        return httpFail(kind, code, body, null);
    }

    /**
     * HTTP 失败统一处理（带厂商上下文）：
     *   - 对 Dashscope + 404 明确提示"兼容模式仅 chat/embeddings，生图/TTS/ASR/视频必须走原生接口"
     *   - 其余错误沿用通用文案
     */
    private MediaResponse httpFail(String kind, int code, String body, ProviderVendor vendor) {
        String msg;
        try {
            JsonNode err = objectMapper.readTree(body).path("error");
            String m = err.path("message").asText(null);
            if (!StringUtils.hasText(m)) {
                JsonNode codeNode = objectMapper.readTree(body).path("code");
                JsonNode msgNode  = objectMapper.readTree(body).path("message");
                if (codeNode != null && msgNode != null && StringUtils.hasText(msgNode.asText(null))) {
                    m = "code=" + codeNode.asText("") + " / message=" + msgNode.asText("");
                }
            }
            if (!StringUtils.hasText(m)) m = err.isTextual() ? err.asText() : null;
            msg = StringUtils.hasText(m) ? m : body;
            if (msg.length() > 320) msg = msg.substring(0, 320) + "…";
        } catch (Exception ignore) { msg = body; }
        String friendly;
        if (code == 404 && vendor == ProviderVendor.DASHSCOPE) {
            // Dashscope 404 99% 是：1) 兼容模式误用于非 chat/embeddings 2) 视频/图片/ASR/TTS 模型ID跨模态混用
            friendly = "❌ 检测到万相/通义原生接口返回 404：" +
                    "① 请确认模型 ID 属于当前模态（图片用 wanx-*-t2i / 视频用 wan*-t2v / TTS 用 cosyvoice 或 qwen-audio-tts / ASR 用 paraformer-v2，不能混用）；" +
                    "② 请勿在 baseUrl 中手动拼接 /v1/images 等兼容接口路径（系统已强制走原生 /api/v1/services/aigc/* 接口）。" +
                    "（详情：" + msg + "）";
        } else {
            friendly = switch (code) {
                case 401 -> "❌ API Key 无效或未授权，请检查 Key 是否填写正确（HTTP 401）。";
                case 403 -> "❌ 账户被拒访问该模型，或配额耗尽（HTTP 403）：" + msg;
                case 404 -> "❌ 模型 ID 或厂商地址有误，请核对预设模型或自定义 baseUrl（HTTP 404）。";
                case 429 -> "❌ 请求过于频繁或额度不足，稍后再试（HTTP 429）：" + msg;
                default -> "❌ 接口调用失败（HTTP " + code + "）：" + msg;
            };
        }
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
