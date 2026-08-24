package org.example.duobaan.service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.dto.QWeatherResponse;
import org.example.duobaan.model.dto.WeatherNow;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 天气服务：调用和风天气（QWeather）实况接口，带 TTL 缓存。
 * 未配置 Key 时返回占位数据，保证平台可独立启动。
 */
@Service
public class WeatherService {

    private final RestClient restClient;
    private final DuobaanProperties props;

    /** 简单缓存：快照 + 抓取时刻 */
    private record Cache(WeatherNow weather, long fetchedAt) {
    }

    private final AtomicReference<Cache> cache = new AtomicReference<>();

    public WeatherService(RestClient externalRestClient, DuobaanProperties props) {
        this.restClient = externalRestClient;
        this.props = props;
    }

    public WeatherNow now() {
        DuobaanProperties.Weather cfg = props.getWeather();
        if (!isConfigured(cfg)) {
            return WeatherNow.placeholder();
        }

        Cache hit = cache.get();
        long ttlMs = cfg.getCacheTtlSeconds() * 1000L;
        if (hit != null && System.currentTimeMillis() - hit.fetchedAt() < ttlMs) {
            return hit.weather();
        }

        WeatherNow fresh = fetchFromQWeather(cfg);
        cache.set(new Cache(fresh, System.currentTimeMillis()));
        return fresh;
    }

    private boolean isConfigured(DuobaanProperties.Weather cfg) {
        return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    private WeatherNow fetchFromQWeather(DuobaanProperties.Weather cfg) {
        try {
            QWeatherResponse resp = restClient.get()
                    .uri("https://devapi.qweather.com/v7/weather/now?location={loc}&key={key}",
                            cfg.getLocation(), cfg.getApiKey())
                    .retrieve()
                    .body(QWeatherResponse.class);

            if (resp == null || resp.now() == null || !"200".equals(resp.code())) {
                return WeatherNow.placeholder();
            }

            QWeatherResponse.Now n = resp.now();
            return new WeatherNow(
                    cfg.getLocation(),
                    n.text(),
                    n.temp(),
                    n.feelsLike(),
                    n.windDir(),
                    n.windScale(),
                    n.humidity(),
                    LocalDateTime.now().toString());
        } catch (Exception e) {
            // 调用失败时回退到占位数据，保证平台不中断
            Cache hit = cache.get();
            if (hit != null) {
                return hit.weather();
            }
            return WeatherNow.placeholder();
        }
    }
}
