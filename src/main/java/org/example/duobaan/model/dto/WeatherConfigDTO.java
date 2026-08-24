package org.example.duobaan.model.dto;

/**
 * 天气服务配置。和风 2026 年起停用公共域名（geoapi/devapi/api.qweather.com），
 * 必须使用每个账号独立的 API Host，形如：xxxxx.def.qweatherapi.com（在控制台-设置里复制）。
 */
public record WeatherConfigDTO(
        String provider,   // qweather（预留扩展）
        String apiHost,    // 必填：形如 https://abc1234xyz.def.qweatherapi.com 或 abc1234xyz.def.qweatherapi.com
        String apiKey,     // 必填
        String location,   // 必填：中文城市名或纯数字 LocationID，默认北京
        long cacheTtlSeconds) {
}
