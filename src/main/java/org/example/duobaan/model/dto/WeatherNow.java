package org.example.duobaan.model.dto;

import java.time.LocalDateTime;

/**
 * 实时天气统一视图（屏蔽不同天气厂商字段差异）。
 *
 * status 三态：
 *   OK            — 真实数据，configured=true
 *   UNCONFIGURED  — 未配置 Key（placeholder），configured=false
 *   ERROR         — 已配置Key，但调用失败（用户可根据 message 排查），configured=false
 */
public record WeatherNow(
        String location,
        String text,
        String temp,
        String feelsLike,
        String windDir,
        String windScale,
        String humidity,
        String updatedAt,
        boolean configured,
        String status,    // OK / UNCONFIGURED / ERROR
        String message    // 当 status=ERROR 时的原因说明（中文）
) {

    private static final String EMPTY = "—";

    /** 未配置 Key 的占位 */
    public static WeatherNow placeholder() {
        return new WeatherNow(
                "未配置城市", EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY,
                LocalDateTime.now().toString(),
                false, "UNCONFIGURED", null);
    }

    /** 调用失败：已配置Key但请求失败 */
    public static WeatherNow error(String message) {
        return new WeatherNow(
                EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY,
                LocalDateTime.now().toString(),
                false, "ERROR", message == null ? "未知错误" : message);
    }

    /** 真实查询结果构造（status=OK，configured=true）*/
    public WeatherNow(String location, String text, String temp, String feelsLike,
                      String windDir, String windScale, String humidity, String updatedAt) {
        this(location, text, temp, feelsLike, windDir, windScale, humidity, updatedAt,
                true, "OK", null);
    }

    /** 大模型上下文摘要 */
    public String summary() {
        if ("UNCONFIGURED".equals(status)) return "未配置天气";
        if ("ERROR".equals(status)) return "天气获取失败：" + message;
        StringBuilder sb = new StringBuilder();
        if (text != null && !text.equals(EMPTY)) sb.append(text);
        if (temp != null && !temp.equals(EMPTY)) sb.append(" ").append(temp).append("℃");
        if (feelsLike != null && !feelsLike.equals(EMPTY)) sb.append(" 体感").append(feelsLike).append("℃");
        if (windDir != null && !windDir.equals(EMPTY)) sb.append(" ").append(windDir).append("风");
        if (windScale != null && !windScale.equals(EMPTY)) sb.append(windScale).append("级");
        return sb.toString().trim();
    }
}
