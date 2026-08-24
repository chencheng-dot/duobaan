package org.example.duobaan.controller;

import java.util.Map;

import org.example.duobaan.model.ChatMode;
import org.example.duobaan.model.dto.MediaResponse;
import org.example.duobaan.service.ChatService;
import org.example.duobaan.service.MediaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public MediaController(MediaService mediaService, ChatService chatService, ObjectMapper objectMapper) {
        this.mediaService = mediaService;
        this.chatService = chatService;
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
            // 把转写结果当「助手文本消息」入库，之后用户可以直接当作上下文给 LLM 用
            ChatMode cm = parseMode(mode);
            chatService.appendAndTrim(cm, "assistant", "[语音转写结果]\n" + res.text());
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

    // ================================= 内部辅助 =================================
    /** 把多模态生成结果作为一条「助手富内容消息」写入 chat_message。
     *  content 格式：富媒体头部 + JSON（小图/TTS/视频 可被前端还原为 img/audio/video 标签） */
    private void persistAsAssistant(String modeStr, String role, String kind, MediaResponse resp,
                                    String fallbackText) {
        ChatMode cm = parseMode(modeStr);
        String content = fallbackText;
        try {
            // 仅当成功时把富内容 JSON 附加到消息里，便于前端渲染
            if ("succeeded".equalsIgnoreCase(resp.status())) {
                Map<String, Object> rich = Map.of(
                        "kind", kind,
                        "payload", objectMapper.convertValue(resp, Object.class),
                        "text", fallbackText
                );
                content = "%%RICH_MEDIA%%" + objectMapper.writeValueAsString(rich);
            }
        } catch (Exception ignore) {
        }
        chatService.appendAndTrim(cm, role, content);
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
