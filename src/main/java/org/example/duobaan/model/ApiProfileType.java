package org.example.duobaan.model;

/**
 * API 配置的业务类型（按模态划分）：
 * - LLM：文本对话模型（chat/completions），原大模型Tab
 * - IMAGE：文生图 / 图生图（images/generations，如 DALL·E 3 / 混元生图）
 * - AUDIO：语音生成（TTS /audio/speech）与 语音转写（ASR /audio/transcriptions），同一 profile 两用
 * - VIDEO：文生视频（videos/generations，如 Seedance / 可灵）
 * - WEATHER：和风天气（不在多模态范畴，保留独立逻辑）
 *  持久化对应列由 MySQL ENUM 升级为 VARCHAR(20)，
 *  方便以后扩展新类型（如 SEARCH、EMBEDDING）时无需再改表结构 DDL。
 */
public enum ApiProfileType {
    LLM,
    IMAGE,
    AUDIO,
    VIDEO,
    WEATHER
}
