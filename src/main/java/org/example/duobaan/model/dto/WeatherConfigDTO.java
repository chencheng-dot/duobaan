package org.example.duobaan.model.dto;

/**
 * 天气服务配置 DTO。
 * provider: qweather(和风天气) 等
 * location: 城市 ID，默认北京 101010100
 */
public record WeatherConfigDTO(
        String provider,
        String apiKey,
        String location,
        long cacheTtlSeconds) {
}
