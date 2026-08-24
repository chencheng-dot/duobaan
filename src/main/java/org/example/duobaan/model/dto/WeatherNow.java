package org.example.duobaan.model.dto;

import java.time.LocalDateTime;

/**
 * 实时天气统一视图（屏蔽不同天气厂商字段差异）。
 */
public record WeatherNow(
        String location,
        String text,
        String temp,
        String feelsLike,
        String windDir,
        String windScale,
        String humidity,
        String updatedAt) {

    /** 无 Key 时的占位天气 */
    public static WeatherNow placeholder() {
        return new WeatherNow(
                "未配置城市",
                "晴",
                "—",
                "—",
                "—",
                "—",
                "—",
                LocalDateTime.now().toString());
    }

    /** 供大模型上下文的简短摘要 */
    public String summary() {
        return text + " " + temp + "℃ 体感" + feelsLike + "℃ " + windDir + "风" + windScale + "级";
    }
}
