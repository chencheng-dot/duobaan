package org.example.duobaan.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 和风天气实况天气接口响应（仅保留需要的字段）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QWeatherResponse(String code, Now now) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Now(
            String temp,
            String feelsLike,
            String text,
            String windDir,
            String windScale,
            String humidity) {
    }
}
