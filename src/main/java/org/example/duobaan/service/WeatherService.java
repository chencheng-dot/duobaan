package org.example.duobaan.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.dto.QWeatherResponse;
import org.example.duobaan.model.dto.WeatherConfigDTO;
import org.example.duobaan.model.dto.WeatherNow;
import org.example.duobaan.util.OutboundUrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 天气服务：调用和风天气（QWeather）实况接口，带 TTL 缓存。
 * location 支持两种形式：
 *   1. 纯数字 LocationID（如 101010100）：直接使用
 *   2. 中文城市名（如 北京、上海、成都）：先走 GeoAPI /v2/city/lookup 解析为 LocationID
 *
 * 返回 WeatherNow 分三态：
 *   status=UNCONFIGURED — 未填 API Key（占位）
 *   status=ERROR        — 已填 Key，但 GeoAPI 或 weather/now 调用失败，message 写明原因
 *   status=OK           — 查询成功
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final RestClient restClient;
    private final DuobaanProperties props;
    private final ConfigService configService;
    private final ApiProfileService apiProfileService;

    private record Cache(WeatherNow weather, long fetchedAt) {
    }

    private record GeoEntry(String rawCity, String locationId, long resolvedAt) {
    }

    private final AtomicReference<Cache> cache = new AtomicReference<>();
    private final AtomicReference<WeatherConfigDTO> lastCfg = new AtomicReference<>();
    private final AtomicReference<GeoEntry> geoCache = new AtomicReference<>();

    public WeatherService(RestClient externalRestClient, DuobaanProperties props,
                          ConfigService configService, ApiProfileService apiProfileService) {
        this.restClient = externalRestClient;
        this.props = props;
        this.configService = configService;
        this.apiProfileService = apiProfileService;
    }

    private WeatherConfigDTO currentConfig() {
        return apiProfileService.getActivePlain(org.example.duobaan.model.ApiProfileType.WEATHER)
                .map(p -> new WeatherConfigDTO(
                        p.getProvider() == null ? "qweather" : p.getProvider(),
                        p.getBaseUrl() == null ? "" : p.getBaseUrl(),
                        p.getApiKey(),
                        p.getLocation() == null ? props.getWeather().getLocation() : p.getLocation(),
                        p.getCacheTtlSeconds() == null || p.getCacheTtlSeconds() <= 0
                                ? props.getWeather().getCacheTtlSeconds() : p.getCacheTtlSeconds()))
                .orElseGet(configService::getWeatherConfig);
    }

    public WeatherNow now() {
        WeatherConfigDTO cfg = currentConfig();
        WeatherConfigDTO prev = lastCfg.get();
        if (prev == null || !prev.equals(cfg)) {
            cache.set(null);
            lastCfg.set(cfg);
        }
        String requireMsg = requireConfigured(cfg);
        if (requireMsg != null) {
            Cache hit = cache.get();
            if (hit != null && "OK".equals(hit.weather().status())) return hit.weather();
            return WeatherNow.error(requireMsg + "（请前往「设置 → 天气服务」填写完整）");
        }
        String host = normalizeHost(cfg.apiHost());
        String locationId;
        try {
            locationId = resolveLocationId(cfg, host);
        } catch (RuntimeException e) {
            Cache hit = cache.get();
            if (hit != null && "OK".equals(hit.weather().status())) return hit.weather();
            return WeatherNow.error("城市解析失败：" + e.getMessage());
        }
        Cache hit = cache.get();
        long ttlMs = cfg.cacheTtlSeconds() * 1000L;
        if (hit != null && "OK".equals(hit.weather().status())
                && System.currentTimeMillis() - hit.fetchedAt() < ttlMs) {
            return hit.weather();
        }
        WeatherNow fresh = fetchFromQWeather(cfg, host, locationId);
        if ("OK".equals(fresh.status())) {
            cache.set(new Cache(fresh, System.currentTimeMillis()));
        } else {
            if (hit != null && "OK".equals(hit.weather().status())
                    && fresh.message() != null && fresh.message().contains("5")) {
                return hit.weather();
            }
        }
        return fresh;
    }

    /** 对必填项做校验，缺失时返回中文提示（用于直接展示给用户） */
    private String requireConfigured(WeatherConfigDTO cfg) {
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            return "未填写 API Key";
        }
        String host = cfg.apiHost();
        if (host == null || host.isBlank() || "null".equalsIgnoreCase(host)) {
            return "未填写 API Host（和风 2026 年起已停用旧公共域名 geoapi/devapi.qweather.com，请到控制台复制个人专属 Host）";
        }
        if (!host.matches("(?i)^(https?://)?[A-Za-z0-9._-]+\\.qweatherapi\\.com(/)?$")) {
            return "API Host 格式不正确，应为 xxx.def.qweatherapi.com（不要包含路径，可带 https:// 前缀）";
        }
        return null;
    }

    private static String normalizeHost(String raw) {
        String h = raw == null ? "" : raw.trim();
        h = h.replaceAll("/+$", "");
        if (h.isEmpty()) return h;
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "https://" + h;
        return h;
    }

    private String resolveLocationId(WeatherConfigDTO cfg, String host) {
        String loc = cfg.location() == null ? "" : cfg.location().trim();
        if (loc.isEmpty()) loc = "北京";
        if (loc.matches("\\d+")) return loc;

        GeoEntry cached = geoCache.get();
        long geoTtl = 24L * 60 * 60 * 1000;
        if (cached != null && loc.equals(cached.rawCity())
                && System.currentTimeMillis() - cached.resolvedAt() < geoTtl) {
            return cached.locationId();
        }
        String resolved = geoLookup(loc, cfg.apiKey(), host);
        geoCache.set(new GeoEntry(loc, resolved, System.currentTimeMillis()));
        return resolved;
    }

    /** GeoAPI /geo/v2/city/lookup — 2026 起用个人 API Host，路径为 /geo/v2/city/lookup */
    private String geoLookup(String cityName, String apiKey, String host) {
        OutboundUrlGuard.verify(host + "/geo/v2/city/lookup");   // 出网地址守卫：阻断内网/环回/元数据地址
        try {
            GeoResponse resp = restClient.get()
                    .uri(host + "/geo/v2/city/lookup?location={loc}&key={key}",
                            cityName, apiKey)
                    .retrieve()
                    .body(GeoResponse.class);
            if (resp == null) {
                throw new RuntimeException("GeoAPI 无响应（请检查 API Host 与网络）");
            }
            String code = resp.code() == null ? "null" : resp.code();
            switch (code) {
                case "200":
                    if (resp.location() == null || resp.location().isEmpty()) {
                        throw new RuntimeException("城市[" + cityName + "]未找到，请换具体市/区名称或直接使用数字 LocationID");
                    }
                    return resp.location().get(0).id();
                case "401": throw new RuntimeException("API Key 无效或未授权 GeoAPI（检查和风控制台「指定API」是否勾选 GeoAPI）");
                case "402": throw new RuntimeException("账号欠费，请前往 console.qweather.com 充值");
                case "403": throw new RuntimeException("API Host 不匹配或项目无权限调用 GeoAPI（请在项目里勾选 GeoAPI，并确认使用的是你账户自己的 API Host）");
                case "404": throw new RuntimeException("城市[" + cityName + "]未找到（注意填写具体城市，不要用省名；例如把“四川”改为“成都”）");
                case "429": throw new RuntimeException("调用过于频繁（超过 QPM，请稍后重试）");
                case "500": case "501": throw new RuntimeException("和风 GeoAPI 服务器异常（code=" + code + "）");
                default: throw new RuntimeException("GeoAPI 返回 code=" + code);
            }
        } catch (HttpClientErrorException e) {
            int s = e.getStatusCode().value();
            if (s == 404) {
                throw new RuntimeException("API Host 路径 404（请确认 Host 形如 xxx.def.qweatherapi.com，路径已为 /geo/v2/city/lookup；旧的 geoapi.qweather.com 已停用）");
            }
            if (s == 401 || s == 403) {
                throw new RuntimeException("API Key / Host 鉴权失败（HTTP " + s + "），请核对 Key、确认 Host 归属该 Key、以及在凭据「指定API」里勾选 GeoAPI");
            }
            log.warn("GeoAPI HTTP {} 调用失败", s, e);
            throw new RuntimeException("GeoAPI HTTP " + s + "：天气服务暂时不可用，请稍后重试。");
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.warn("GeoAPI 调用失败", e);
            throw new RuntimeException("GeoAPI 调用失败：天气服务暂时不可用，请稍后重试。");
        }
    }

    /** GeoAPI /v2/city/lookup 响应的最小结构 */
    public record GeoResponse(String code, List<GeoLocation> location) {
        public record GeoLocation(String id, String name, String adm1, String country) {
        }
    }

    private WeatherNow fetchFromQWeather(WeatherConfigDTO cfg, String host, String locationId) {
        final String URL = host + "/v7/weather/now?location=" + locationId + "&key=" + cfg.apiKey();
        try {
            // 守卫必须放在 try 内：它拒绝时抛的是 IllegalArgumentException（DNS 解析失败 / 解析到内网/环回）。
            // 若留在 try 之外，会一路冒泡成 HTTP 400，把「天气不可用」升级成聊天
            // （LlmController.buildSystemContext）与餐食推荐（RecommendService）的硬失败；
            // 放在 try 内则降级为一条错误文案，符合「天气挂了聊天照常」的原有设计。
            OutboundUrlGuard.verify(URL);   // 出网地址守卫：阻断内网/环回/元数据地址
            QWeatherResponse resp = restClient.get()
                    .uri(URL)
                    .retrieve()
                    .body(QWeatherResponse.class);

            if (resp == null) return WeatherNow.error("天气接口无响应（检查 API Host 与网络）");
            String code = resp.code() == null ? "null" : resp.code();
            switch (code) {
                case "200":
                    if (resp.now() == null) return WeatherNow.error("天气返回数据为空");
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
                case "401": return WeatherNow.error("API Key 无效（401）— 请到设置页核对 Key 或检查「天气预报」是否已勾选到指定 API 列表");
                case "402": return WeatherNow.error("账号欠费（402）— 请到 console.qweather.com 充值或升级套餐");
                case "403": return WeatherNow.error("Host/项目无权限调用天气预报（403）— 请确认 Host 与该 Key 匹配、并在凭据「指定API」里勾选天气预报");
                case "404": return WeatherNow.error("LocationID [" + locationId + "] 无数据（404），或 API Host 格式错误（旧公共域名 geoapi/devapi.qweather.com 已停用，请用 xxx.def.qweatherapi.com）");
                case "429": return WeatherNow.error("调用过于频繁（429，QPM超限）");
                case "500": case "501": return WeatherNow.error("和风天气服务器异常（code=" + code + "），稍后再试");
                default: return WeatherNow.error("天气预报接口返回 code=" + code);
            }
        } catch (HttpClientErrorException e) {
            HttpStatusCode s = e.getStatusCode();
            String msg = "天气预报 HTTP " + s.value() + "：";
            if (s.value() == 404) {
                msg += "API Host 路径 404（旧公共域名 geoapi/devapi/api.qweather.com 自 2026 起已停用，请在控制台-设置里复制你的个人 API Host，形如 xxx.def.qweatherapi.com）";
            } else if (s.equals(HttpStatus.UNAUTHORIZED) || s.value() == 403) {
                msg += "API Key / Host 鉴权失败：请核对 Key、Host 与项目对应、并在凭据「指定API」里勾选天气预报";
            } else {
                log.warn("天气预报 HTTP {} 调用失败", s.value(), e);
                msg += "天气服务暂时不可用（HTTP " + s.value() + "），请稍后重试。";
            }
            Cache hit = cache.get();
            if (hit != null && "OK".equals(hit.weather().status()) && s.is5xxServerError()) return hit.weather();
            return WeatherNow.error(msg);
        } catch (Exception e) {
            Cache hit = cache.get();
            if (hit != null && "OK".equals(hit.weather().status())) return hit.weather();
            log.warn("天气预报调用失败", e);
            return WeatherNow.error("天气预报调用失败，请稍后重试。");
        }
    }
}
