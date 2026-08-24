package org.example.duobaan.model.dto;

import java.time.LocalDateTime;

/**
 * 实时天气统一视图（屏蔽不同天气厂商字段差异）。
 * configured=false 表示当前为占位数据（未配置 Key 或调用失败），前端应显示占位态。
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
        boolean configured) {

    /** 无 Key 时的占位天气 */
    public static WeatherNow placeholder() {
        return new WeatherNow(
                "未配置城市",
                "—",
                "—",
                "—",
                "—",
                "—",
                "—",
                LocalDateTime.now().toString(),
                false);
    }

    /** 为真实查询结果补 configured=true 的便捷构造 */
    public WeatherNow(String location, String text, String temp, String feelsLike,
                      String windDir, String windScale, String humidity, String updatedAt) {
        this(location, text, temp, feelsLike, windDir, windScale, humidity, updatedAt, true);
    }

    /** 供大模型上下文的简短摘要；占位时返回提示 */
    public String summary() {
        if (!configured) return "未配置天气";
        StringBuilder sb = new StringBuilder();
        if (text != null && !text.equals("—")) sb.append(text);
        if (temp != null && !temp.equals("—")) sb.append(" ").append(temp).append("℃");
        if (feelsLike != null && !feelsLike.equals("—")) sb.append(" 体感").append(feelsLike).append("℃");
        if (windDir != null && !windDir.equals("—")) sb.append(" ").append(windDir).append("风");
        if (windScale != null && !windScale.equals("—")) sb.append(windScale).append("级");
        return sb.toString().trim();
    }
}
