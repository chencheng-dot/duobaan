package org.example.duobaan.model.dto;

import java.util.List;

/**
 * 多模态统一响应结构：
 *  - 文生图：每个 item.url 是图片直链（或 b64Data base64 编码 png/jpeg）
 *  - 文生视频：每个 item.url 是 mp4 直链；status = pending / succeeded / failed
 *  - 语音转写：text 字段非空时直接读，items 为空
 *
 * 注意：本 record 返回给前端时由 Jackson 序列化为 JSON，
 * 因此 TTS 的 audioBytes 一定会被 base64 编码后随 JSON 传输（体积 +33%），
 * 「响应体直接流式回传、不经过 JSON」的说法并不成立。
 * 若要做真正的二进制流式下载，需要把对应 Controller 改成返回 ResponseEntity&lt;byte[]&gt;。
 * 另外 MediaController 写库时已剔除 audioBytes 与超大 b64Data，避免 chat_message 膨胀。
 */
public record MediaResponse(
        String kind,          // IMAGE / AUDIO_TTS / AUDIO_ASR / VIDEO / ERROR
        String status,        // succeeded / failed / pending / degraded
        String error,         // 失败时的用户友好错误（非技术堆栈）
        String text,          // 仅 ASR 转写返回文本
        byte[] audioBytes,    // 仅 TTS 返回二进制；经 Jackson 序列化后会变成 base64 字符串（+33% 体积）
        String audioMime,     // TTS 的 Content-Type，例 audio/mpeg
        String audioFilename, // TTS 建议保存名，例 speech.mp3
        String message,       // 可选的人读提示（万相异步 task_id 说明、操作指引等）
        List<MediaItem> items, // 图/视频：每张/每段一条
        Long profileId        // 关联的 ApiProfile ID，前端用于异步任务轮询
) {
    public static MediaResponse image(List<MediaItem> items) {
        return new MediaResponse("IMAGE", "succeeded", null, null, null, null, null, null, items, null);
    }
    public static MediaResponse image(List<MediaItem> items, Long profileId) {
        return new MediaResponse("IMAGE", "succeeded", null, null, null, null, null, null, items, profileId);
    }
    public static MediaResponse video(List<MediaItem> items, String status) {
        return new MediaResponse("VIDEO", status, null, null, null, null, null, null, items, null);
    }
    public static MediaResponse video(List<MediaItem> items, String status, String message) {
        return new MediaResponse("VIDEO", status, null, null, null, null, null, message, items, null);
    }
    public static MediaResponse video(List<MediaItem> items, String status, String message, Long profileId) {
        return new MediaResponse("VIDEO", status, null, null, null, null, null, message, items, profileId);
    }
    public static MediaResponse asr(String text) {
        return new MediaResponse("AUDIO_ASR", "succeeded", null, text, null, null, null, null, List.of(), null);
    }
    public static MediaResponse tts(byte[] bytes, String mime, String filename) {
        return new MediaResponse("AUDIO_TTS", "succeeded", null, null, bytes, mime, filename, null, List.of(), null);
    }
    public static MediaResponse ttsPending(String message) {
        return new MediaResponse("AUDIO_TTS", "pending", null, null, null, null, null, message, List.of(), null);
    }
    public static MediaResponse error(String kind, String friendly) {
        return new MediaResponse(kind, "degraded", friendly, null, null, null, null, null, List.of(), null);
    }
    /** 图片 pending：Dashscope 异步任务已提交但暂未返回结果 URL */
    public static MediaResponse image(List<MediaItem> items, String status, String message) {
        return new MediaResponse("IMAGE", status, null, null, null, null, null, message, items, null);
    }
    public static MediaResponse image(List<MediaItem> items, String status, String message, Long profileId) {
        return new MediaResponse("IMAGE", status, null, null, null, null, null, message, items, profileId);
    }

    /** 单个图片 / 视频条目 */
    public record MediaItem(
            String url,           // 直链（优先）。空时尝试 b64Data
            String b64Data,       // base64 数据（不含 data:xxx;base64, 前缀，由前端补）
            String revisedPrompt  // 模型改写后的提示词（DALL·E 常见）
    ) {}
}
