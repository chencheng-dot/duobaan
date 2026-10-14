package org.example.duobaan.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.ApiProfile;
import org.example.duobaan.model.ApiProfileType;
import org.example.duobaan.model.dto.ApiProfileDTO;
import org.example.duobaan.model.dto.ApiProfileInbound;
import org.example.duobaan.model.dto.LlmConfigDTO;
import org.example.duobaan.model.dto.WeatherConfigDTO;
import org.example.duobaan.service.ApiProfileService;
import org.example.duobaan.service.ConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    /** 旧 /config/llm 接口保存的自动配置名 */
    private static final String AUTO_LLM_NAME = "系统自动保存-LLM";

    /** 旧 /config/weather 接口保存的自动配置名 */
    private static final String AUTO_WEATHER_NAME = "系统自动保存-WEATHER";

    private final ConfigService configService;   // 保留：/providers 仍在此
    private final ApiProfileService apiProfileService;
    private final DuobaanProperties props;

    public ConfigController(ConfigService configService,
                            ApiProfileService apiProfileService,
                            DuobaanProperties props) {
        this.configService = configService;
        this.apiProfileService = apiProfileService;
        this.props = props;
    }

    // =============== 厂商预设接口 ===============
    /** 保留旧版：仅返回 LLM 文本模型厂商列表，兼容 v3.0 之前前端 */
    @GetMapping("/providers")
    public List<ConfigService.ProviderPreset> getProviders() {
        return configService.getProviderPresets();
    }

    /** 多模态：一次返回 5 种类型（LLM/IMAGE/AUDIO/VIDEO/WEATHER）各自的厂商预设，前端按 Tab 分组渲染 */
    @GetMapping("/providers/all")
    public Map<ApiProfileType, List<ConfigService.ProviderPreset>> getProvidersAll() {
        return configService.getProviderPresetsByType();
    }

    // ==================================================================
    // 新 profiles 家族接口：/api/config/profiles?type=LLM|WEATHER
    // ==================================================================

    @GetMapping("/profiles")
    public List<ApiProfileDTO> list(@RequestParam ApiProfileType type) {
        return apiProfileService.list(type);
    }

    @GetMapping("/profiles/active")
    public ResponseEntity<ApiProfileDTO> active(@RequestParam ApiProfileType type) {
        Optional<ApiProfileDTO> active = apiProfileService.getActive(type);
        return active.map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/profiles/{id}")
    public ResponseEntity<ApiProfileDTO> getOne(@PathVariable Long id) {
        return ResponseEntity.of(apiProfileService.get(id));
    }

    @PostMapping("/profiles")
    public ApiProfileDTO create(@RequestBody ApiProfileInbound in) {
        return apiProfileService.create(in);
    }

    @PutMapping("/profiles/{id}")
    public ApiProfileDTO update(@PathVariable Long id, @RequestBody ApiProfileInbound in) {
        return apiProfileService.update(id, in);
    }

    @PostMapping("/profiles/{id}/activate")
    public ApiProfileDTO activate(@PathVariable Long id) {
        return apiProfileService.setActive(id);
    }

    @DeleteMapping("/profiles/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        // 检查被删的是否 active，如是，返回带中文提示
        Optional<ApiProfileDTO> before = apiProfileService.get(id);
        boolean wasActive = before.map(ApiProfileDTO::isActive).orElse(false);
        apiProfileService.delete(id);
        Map<String, Object> body = new HashMap<>();
        body.put("deleted", true);
        if (wasActive) {
            body.put("warning", "当前默认配置已删除，该类型不再有默认配置，后续对话/天气将提示「请先配置…」。");
        }
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    // ==================================================================
    // 兼容旧接口（不破坏 v2.1 的前端或移动端 /config/llm /weather）
    // ==================================================================

    @GetMapping("/llm")
    public LlmConfigDTO getLlmConfig() {
        Optional<ApiProfile> active = apiProfileService.getActivePlain(ApiProfileType.LLM);
        if (active.isPresent()) {
            ApiProfile p = active.get();
            // 对外接口永远不返回明文 Key（打码）
            return new LlmConfigDTO(
                    p.getProvider(),
                    p.getBaseUrl(),
                    ApiProfileService.maskKey(p.getApiKey()),
                    p.getModel(),
                    p.getTimeoutSeconds() == null ? props.getLlm().getTimeoutSeconds() : p.getTimeoutSeconds());
        }
        // system_config 走 ConfigService，但旧 llm DTO 里的 apiKey 也打码
        LlmConfigDTO fromCfg = configService.getLlmConfig();
        return new LlmConfigDTO(fromCfg.provider(), fromCfg.baseUrl(),
                ApiProfileService.maskKey(fromCfg.apiKey()),
                fromCfg.model(), fromCfg.timeoutSeconds());
    }

    @PostMapping("/llm")
    @Deprecated
    public LlmConfigDTO saveLlmConfig(@RequestBody LlmConfigDTO dto) {
        // 旧保存行为原先每次都新建 profile，长期使用会在列表里堆积大量同名「系统自动保存-LLM」。
        // 现改为：已存在同名自动配置则原地更新它，否则才新建。请新前端改用 /api/config/profiles。
        ApiProfileInbound in = new ApiProfileInbound(
                ApiProfileType.LLM, AUTO_LLM_NAME,
                dto.provider(), dto.baseUrl(), dto.model(), dto.apiKey(),
                null, null, dto.timeoutSeconds(), true);
        Optional<Long> existingId = apiProfileService.list(ApiProfileType.LLM).stream()
                .filter(p -> AUTO_LLM_NAME.equals(p.name()))
                .map(ApiProfileDTO::id)
                .findFirst();
        ApiProfileDTO saved = existingId
                .map(id -> apiProfileService.update(id, in))
                .orElseGet(() -> apiProfileService.create(in));
        return new LlmConfigDTO(saved.provider(), saved.baseUrl(), "", saved.model(), saved.timeoutSeconds());
    }

    @GetMapping("/weather")
    public WeatherConfigDTO getWeatherConfig() {
        Optional<ApiProfile> active = apiProfileService.getActivePlain(ApiProfileType.WEATHER);
        if (active.isPresent()) {
            ApiProfile p = active.get();
            // 对外返回 WeatherConfigDTO：旧前端里我们也不显示 apiKey，安全起见这里 apiKey 置空打码
            String host = p.getBaseUrl() == null ? "" : p.getBaseUrl();
            return new WeatherConfigDTO(
                    p.getProvider() == null ? "qweather" : p.getProvider(),
                    host,
                    ApiProfileService.maskKey(p.getApiKey()),
                    p.getLocation() == null ? "" : p.getLocation(),
                    p.getCacheTtlSeconds() == null ? 600L : p.getCacheTtlSeconds());
        }
        // 回退分支（无 active WEATHER 配置）走遗留 system_config / properties，其 apiKey 是明文。
        // 必须和上面分支一样打码后再对外，否则 GET /api/config/weather 会直接回吐天气 API Key。
        WeatherConfigDTO legacy = configService.getWeatherConfig();
        return new WeatherConfigDTO(
                legacy.provider(),
                legacy.apiHost(),
                ApiProfileService.maskKey(legacy.apiKey()),
                legacy.location(),
                legacy.cacheTtlSeconds());
    }

    @PostMapping("/weather")
    public WeatherConfigDTO saveWeatherConfig(@RequestBody WeatherConfigDTO dto) {
        // 与 saveLlmConfig 同一处理：旧行为每次都新建 profile，长期使用会堆积大量同名「系统自动保存-WEATHER」。
        // 现改为：已存在同名自动配置则原地更新它（空 Key 会保留原 Key），否则才新建。请新前端改用 /api/config/profiles。
        ApiProfileInbound in = new ApiProfileInbound(
                ApiProfileType.WEATHER, AUTO_WEATHER_NAME,
                "qweather", dto.apiHost(), null, dto.apiKey(),
                dto.location(), dto.cacheTtlSeconds(), null, true);
        Optional<Long> existingId = apiProfileService.list(ApiProfileType.WEATHER).stream()
                .filter(p -> AUTO_WEATHER_NAME.equals(p.name()))
                .map(ApiProfileDTO::id)
                .findFirst();
        ApiProfileDTO saved = existingId
                .map(id -> apiProfileService.update(id, in))
                .orElseGet(() -> apiProfileService.create(in));
        return new WeatherConfigDTO(
                saved.provider(), saved.baseUrl(), "",
                saved.location(), saved.cacheTtlSeconds() == null ? 600L : saved.cacheTtlSeconds());
    }
}
