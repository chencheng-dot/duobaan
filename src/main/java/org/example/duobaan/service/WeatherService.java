package org.example.duobaan.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.dto.QWeatherResponse;
import org.example.duobaan.model.dto.WeatherConfigDTO;
import org.example.duobaan.model.dto.WeatherNow;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 天气服务：调用和风天气（QWeather）实况接口，带 TTL 缓存。
 * location 支持两种形式：
 *   1. 纯数字 LocationID（如 101010100）：直接使用
 *   2. 中文城市名（如 北京、上海、成都）：先走 GeoAPI /v2/city/lookup 解析为 LocationID
 * 配置优先级：数据库运行时配置 > application.properties。
 * 未配置 Key 时返回占位数据，保证平台可独立启动。
 */
@Service
public class WeatherService {

    private final RestClient restClient;
    private final DuobaanProperties props;
    private final ConfigService configService;

    private record Cache(WeatherNow weather, long fetchedAt) {
    }

    private record GeoEntry(String rawCity, String locationId, long resolvedAt) {
    }

    private final AtomicReference<Cache> cache = new AtomicReference<>();
    private final AtomicReference<WeatherConfigDTO> lastCfg = new AtomicReference<>();
    private final AtomicReference<GeoEntry> geoCache = new AtomicReference<>();

    public WeatherService(RestClient externalRestClient, DuobaanProperties props, ConfigService configService) {
        this.restClient = externalRestClient;
        this.props = props;
        this.configService = configService;
    }

    private WeatherConfigDTO currentConfig() {
        WeatherConfigDTO saved = configService.getWeatherConfig();
        if (saved == null) {
            DuobaanProperties.Weather w = props.getWeather();
            return new WeatherConfigDTO(w.getProvider(), w.getApiKey(), w.getLocation(), w.getCacheTtlSeconds());
        }
        return saved;
    }

    public WeatherNow now() {
        WeatherConfigDTO cfg = currentConfig();
        WeatherConfigDTO prev = lastCfg.get();
        if (prev == null || !prev.equals(cfg)) {
            cache.set(null);
            lastCfg.set(cfg);
        }
        if (!isConfigured(cfg)) {
            return WeatherNow.placeholder();
        }
        // 解析出 locationId（数字ID 直接用，中文城市名调用 GeoAPI 解析）
        String locationId;
        try {
            locationId = resolveLocationId(cfg);
        } catch (Exception e) {
            return WeatherNow.placeholder();
        }
        Cache hit = cache.get();
        long ttlMs = cfg.cacheTtlSeconds() * 1000L;
        if (hit != null && System.currentTimeMillis() - hit.fetchedAt() < ttlMs) {
            return hit.weather();
        }
        WeatherNow fresh = fetchFromQWeather(cfg, locationId);
        cache.set(new Cache(fresh, System.currentTimeMillis()));
        return fresh;
    }

    private boolean isConfigured(WeatherConfigDTO cfg) {
        return cfg.apiKey() != null && !cfg.apiKey().isBlank();
    }

    /**
     * 纯数字直接用，否则走 GeoAPI。Geo 解析有一份 24h 的缓存，避免重复查询。
     */
    private String resolveLocationId(WeatherConfigDTO cfg) {
        String loc = cfg.location() == null ? "" : cfg.location().trim();
        if (loc.isEmpty()) loc = "北京";
        if (loc.matches("\\d+")) return loc;

        GeoEntry cached = geoCache.get();
        long geoTtl = 24L * 60 * 60 * 1000;
        if (cached != null && loc.equals(cached.rawCity())
                && System.currentTimeMillis() - cached.resolvedAt() < geoTtl) {
            return cached.locationId();
        }
        String resolved = geoLookup(loc, cfg.apiKey());
        geoCache.set(new GeoEntry(loc, resolved, System.currentTimeMillis()));
        return resolved;
    }

    /**
     * 和风天气城市查询：https://dev.qweather.com/docs/api/geoapi/city-lookup/
     * 返回列表第一个结果的 location id，失败抛异常。
     */
    private String geoLookup(String cityName, String apiKey) {
        try {
            GeoResponse resp = restClient.get()
                    .uri("https://geoapi.qweather.com/v2/city/lookup?location={loc}&key={key}",
                            cityName, apiKey)
                    .retrieve()
                    .body(GeoResponse.class);
            if (resp == null || resp.location() == null || resp.location().isEmpty()
                    || !"200".equals(resp.code())) {
                throw new RuntimeException("城市 [" + cityName + "] 未找到，code=" + (resp == null ? "null" : resp.code()));
            }
            return resp.location().get(0).id();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("城市解析失败：" + e.getMessage(), e);
        }
    }

    /** GeoAPI /v2/city/lookup 响应的最小结构 */
    public record GeoResponse(String code, List<GeoLocation> location) {
        public record GeoLocation(String id, String name, String adm1, String country) {
        }
    }

    private WeatherNow fetchFromQWeather(WeatherConfigDTO cfg, String locationId) {
        try {
            QWeatherResponse resp = restClient.get()
                    .uri("https://devapi.qweather.com/v7/weather/now?location={loc}&key={key}",
                            locationId, cfg.apiKey())
                    .retrieve()
                    .body(QWeatherResponse.class);

            if (resp == null || resp.now() == null || !"200".equals(resp.code())) {
                return WeatherNow.placeholder();
            }

            QWeatherResponse.Now n = resp.now();
            return new WeatherNow(
                    locationId,
                    n.text(),
                    n.temp(),
                    n.feelsLike(),
                    n.windDir(),
                    n.windScale(),
                    n.humidity(),
                    LocalDateTime.now().toString());
        } catch (Exception e) {
            Cache hit = cache.get();
            if (hit != null) return hit.weather();
            return WeatherNow.placeholder();
        }
    }
}
