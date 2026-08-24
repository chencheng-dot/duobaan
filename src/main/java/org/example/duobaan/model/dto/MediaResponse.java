package org.example.duobaan.model.dto;

import java.util.List;

/**
 * 多模态统一响应结构：
 *  - 文生图：每个 item.url 是图片直链（或 b64Data base64 编码 png/jpeg）
 *  - 文生视频：每个 item.url 是 mp4 直链；status = pending / succeeded / failed
 *  - 语音转写：text 字段非空时直接读，items 为空
 */
public record MediaResponse(
        String kind,          // IMAGE / AUDIO_TTS / AUDIO_ASR / VIDEO / ERROR
        String status,        // succeeded / failed / pending / degraded
        String error,         // 失败时的用户友好错误（非技术堆栈）
        String text,          // 仅 ASR 转写返回文本
        byte[] audioBytes,    // 仅 TTS 返回二进制（响应体直接流式回传前端不经过 JSON）
        String audioMime,     // TTS 的 Content-Type，例 audio/mpeg
        String audioFilename, // TTS 建议保存名，例 speech.mp3
        String message,       // 可选的人读提示（万相异步 task_id 说明、操作指引等）
        List<MediaItem> items // 图/视频：每张/每段一条
) {
    public static MediaResponse image(List<MediaItem> items) {
        return new MediaResponse("IMAGE", "succeeded", null, null, null, null, null, null, items);
    }
    public static MediaResponse video(List<MediaItem> items, String status) {
        return new MediaResponse("VIDEO", status, null, null, null, null, null, null, items);
    }
    /** 视频 pending 时附一条说明（如 Dashscope task_id / request_id 提示）*/
    public static MediaResponse video(List<MediaItem> items, String status, String message) {
        return new MediaResponse("VIDEO", status, null, null, null, null, null, message, items);
    }
    public static MediaResponse asr(String text) {
        return new MediaResponse("AUDIO_ASR", "succeeded", null, text, null, null, null, null, List.of());
    }
    public static MediaResponse tts(byte[] bytes, String mime, String filename) {
        return new MediaResponse("AUDIO_TTS", "succeeded", null, null, bytes, mime, filename, null, List.of());
    }
    public static MediaResponse error(String kind, String friendly) {
        return new MediaResponse(kind, "degraded", friendly, null, null, null, null, null, List.of());
    }

    /** 单个图片 / 视频条目 */
    public record MediaItem(
            String url,           // 直链（优先）。空时尝试 b64Data
            String b64Data,       // base64 数据（不含 data:xxx;base64, 前缀，由前端补）
            String revisedPrompt  // 模型改写后的提示词（DALL·E 常见）
    ) {}
}
