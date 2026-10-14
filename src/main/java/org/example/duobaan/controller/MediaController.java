package org.example.duobaan.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.duobaan.model.ChatMode;
import org.example.duobaan.model.dto.MediaResponse;
import org.example.duobaan.service.ChatAppendRetry;
import org.example.duobaan.service.MediaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 多模态生成接口：用户在 ChatPanel 顶部切换模态后调用。
 * 每个接口返回统一的 MediaResponse，其中：
 *   - IMAGE/VIDEO：items[].url 或 items[].b64Data 作为渲染源
 *   - AUDIO_TTS：audioBytes（Jackson 自动序列化为 base64，前端转 Blob URL 给 audio 标签）
 *   - AUDIO_ASR：text 字段直接给用户或回写到文本输入框
 * 每一种生成，都同时把「用户请求 -> 助手生成结果」写入 chat_message 持久化，
 * 这样刷新页面后多模态结果也能恢复（和 v3.1 里「对话历史 MySQL 持久化」对齐）。
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaService mediaService;
    private final ChatAppendRetry chatAppendRetry;
    private final ObjectMapper objectMapper;

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    public MediaController(MediaService mediaService, ChatAppendRetry chatAppendRetry, ObjectMapper objectMapper) {
        this.mediaService = mediaService;
        this.chatAppendRetry = chatAppendRetry;
        this.objectMapper = objectMapper;
    }

    record ImageReq(String prompt, String size, Integer n, String style, String quality,
                    String mode /* WORK | DOPAMINE */) {}
    record SpeechReq(String input, String voice, String format, Double speed, String mode) {}
    record VideoReq(String prompt, Integer seconds, String ratio, String mode) {}

    // ========================= 文生图 =========================
    @PostMapping("/image")
    public MediaResponse image(@RequestBody ImageReq req) {
        MediaResponse res = mediaService.generateImage(req.prompt(), req.size(), req.n(),
                req.style(), req.quality());
        persistAsAssistant(req.mode(), "assistant", "IMAGE", res,
                "🎨 生成图片：" + req.prompt());
        return res;
    }

    // ========================= 文生语音 TTS =========================
    @PostMapping("/speech")
    public MediaResponse speech(@RequestBody SpeechReq req) {
        MediaResponse res = mediaService.generateSpeech(req.input(), req.voice(),
                req.format(), req.speed());
        persistAsAssistant(req.mode(), "assistant", "AUDIO_TTS", res,
                "🔊 语音朗读：" + req.input());
        return res;
    }

    // ========================= 语音转写 ASR =========================
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaResponse transcribe(@RequestPart("file") MultipartFile file,
                                    @RequestParam(value = "mode", required = false) String mode) {
        MediaResponse res = mediaService.transcribeAudio(file);
        if (res.text() != null && !res.text().isBlank()) {
            // 音频内容属于「用户提供的输入」，不是助手说的话：必须以 user 身份入库。
            // 若按 assistant 入库，任意音频内容都会变成「模型自己说过的话」进入上下文（提示注入 / 角色混淆）。
            ChatMode cm = parseMode(mode);
            chatAppendRetry.appendAndTrimWithRetry(cm, "user", "[用户上传音频的转写结果，非助手发言]\n" + res.text());
        }
        return res;
    }

    // ========================= 文生视频 =========================
    @PostMapping("/video")
    public MediaResponse video(@RequestBody VideoReq req) {
        MediaResponse res = mediaService.generateVideo(req.prompt(), req.seconds(), req.ratio());
        persistAsAssistant(req.mode(), "assistant", "VIDEO", res,
                "🎬 生成视频：" + req.prompt());
        return res;
    }

    // ========================= Dashscope 异步任务轮询 =========================
    /** 前端轮询 Dashscope 异步任务：传 task_id + profile_id，返回最新状态 */
    @GetMapping("/poll-task")
    public MediaResponse pollTask(
            @RequestParam String taskId,
            @RequestParam Long profileId,
            @RequestParam(defaultValue = "IMAGE") String kind) {
        return mediaService.pollTask(taskId, profileId, kind);
    }

    // ================================= 内部辅助 =================================
    /** 把多模态生成结果作为一条「助手富内容消息」写入 chat_message。
     *  content 格式：富媒体头部 + JSON（小图/TTS/视频 可被前端还原为 img/audio/video 标签） */
    private void persistAsAssistant(String modeStr, String role, String kind, MediaResponse resp,
                                    String fallbackText) {
        ChatMode cm = parseMode(modeStr);
        String content = fallbackText;
        try {
            // 成功 / pending 都要保存富内容 JSON，便于前端渲染和刷新后恢复
            // degraded/error 状态只保存纯文本提示（无富媒体数据）
            String status = resp.status();
            if ("succeeded".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status)) {
                Map<String, Object> rich = new java.util.HashMap<>();
                rich.put("kind", kind);
                rich.put("payload", slimPayload(resp));
                rich.put("text", fallbackText);
                content = "%%RICH_MEDIA%%" + objectMapper.writeValueAsString(rich);
            }
        } catch (Exception e) {
            log.warn("富媒体内容序列化失败，降级为纯文本入库：{}", e.getMessage());
        }
        chatAppendRetry.appendAndTrimWithRetry(cm, role, content);
    }

    /**
     * 入库前瘦身：绝不把整段音频和超大 base64 图片写进 chat_message。
     * 原实现直接 convertValue(整个响应)，TTS 的 audioBytes（base64 后 +33%）与图片 b64Data
     * 会以 MEDIUMTEXT 形式进库，每轮历史查询又把它们读回堆里，DB 与堆双膨胀。
     * 这里：音频一律不入库（刷新后重新生成）；图片 b64 在阈值内保留以维持「刷新后仍可看图」，
     * 超过阈值只留 url 并标记 b64Omitted，由前端提示重新生成。
     */
    private static final int MAX_INLINE_B64_CHARS = 200_000;   // ≈150KB 图片

    private static Map<String, Object> slimPayload(MediaResponse resp) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("kind", resp.kind());
        p.put("status", resp.status());
        p.put("message", resp.message());
        p.put("text", resp.text());
        p.put("profileId", resp.profileId());
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        if (resp.items() != null) {
            for (MediaResponse.MediaItem it : resp.items()) {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("url", it.url());
                m.put("revisedPrompt", it.revisedPrompt());
                String b64 = it.b64Data();
                if (b64 != null && b64.length() <= MAX_INLINE_B64_CHARS) {
                    m.put("b64Data", b64);
                } else if (b64 != null) {
                    m.put("b64Omitted", true);
                }
                items.add(m);
            }
        }
        p.put("items", items);
        return p;
    }

    private static ChatMode parseMode(String m) {
        if (m == null || m.isBlank()) return ChatMode.WORK;
        try {
            return ChatMode.valueOf(m.trim().toUpperCase());
        } catch (Exception ignore) {
            return ChatMode.WORK;
        }
    }
}
