package org.example.duobaan.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.ApiProfileType;
import org.example.duobaan.model.SystemConfig;
import org.example.duobaan.model.dto.LlmConfigDTO;
import org.example.duobaan.repository.SystemConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * 系统配置服务：负责大模型/天气等配置的持久化与读取。
 * 优先级：数据库配置 > application.properties 默认值。
 */
@Service
public class ConfigService {

    private static final String LLM_CONFIG_KEY = "llm.config";
    private static final String WEATHER_CONFIG_KEY = "weather.config";

    private final SystemConfigRepository repo;
    private final DuobaanProperties props;
    private final ObjectMapper objectMapper;

    public ConfigService(SystemConfigRepository repo, DuobaanProperties props, ObjectMapper objectMapper) {
        this.repo = repo;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取当前生效的大模型配置：优先读数据库，回退到 application.properties。
     */
    public LlmConfigDTO getLlmConfig() {
        Optional<SystemConfig> saved = repo.findByKey(LLM_CONFIG_KEY);
        if (saved.isPresent()) {
            try {
                return objectMapper.readValue(saved.get().getValue(), LlmConfigDTO.class);
            } catch (Exception e) {
                // JSON 损坏则回退到默认
            }
        }
        // 回退到 application.properties
        DuobaanProperties.Llm p = props.getLlm();
        return new LlmConfigDTO(
                "CUSTOM",
                p.getBaseUrl(),
                p.getApiKey(),
                p.getModel(),
                p.getTimeoutSeconds());
    }

    /**
     * 获取预设提供商列表（前端下拉选择用）— 仅返回 LLM 文本模型，向后兼容旧版单列表。
     */
    public List<ProviderPreset> getProviderPresets() {
        return presetsByType().getOrDefault(ApiProfileType.LLM, List.of());
    }

    /**
     * 多模态场景下，分类型返回厂商预设。
     * 前端 SettingsPage 一次性拉取，分到 5 个 Tab 显示。
     * 每个厂商的 baseUrl 统一写到 /v1 根级别（OpenAI 兼容约定），具体 endpoint（如 /images/generations）
     * 由调用方代码拼接（遵循经验：避免 SDK 再重复拼接路径导致 404）。
     */
    public Map<ApiProfileType, List<ProviderPreset>> getProviderPresetsByType() {
        return presetsByType();
    }

    private static Map<ApiProfileType, List<ProviderPreset>> presetsByType() {
        Map<ApiProfileType, List<ProviderPreset>> map = new LinkedHashMap<>();
        map.put(ApiProfileType.LLM, List.of(
                new ProviderPreset("CHATGPT", "ChatGPT", "https://api.openai.com/v1", "gpt-4o-mini"),
                new ProviderPreset("DEEPSEEK", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
                new ProviderPreset("DOUBAO", "豆包", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k"),
                new ProviderPreset("QIANWEN", "千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
                new ProviderPreset("CUSTOM", "自定义", "", "")));
        map.put(ApiProfileType.IMAGE, List.of(
                new ProviderPreset("DALLE3", "DALL·E 3 (OpenAI)", "https://api.openai.com/v1", "dall-e-3"),
                new ProviderPreset("SEEDREAM", "Seedream 混元生图 (火山)", "https://ark.cn-beijing.volces.com/api/v3", "seedream-t2i-pro-250515"),
                new ProviderPreset("WANXIANG", "万相 (阿里通义)", "https://dashscope.aliyuncs.com/compatible-mode/v1", "wanx2.1-t2i-turbo"),
                new ProviderPreset("CUSTOM", "自定义", "", "")));
        map.put(ApiProfileType.AUDIO, List.of(
                // AUDIO 一个 profile 兼顾 TTS(语音生成) 和 ASR(语音转写)；主存 TTS 模型 ID，ASR 默认 whisper-1 代码内可覆写
                new ProviderPreset("OPENAI_AUDIO", "OpenAI (TTS + Whisper)", "https://api.openai.com/v1", "tts-1"),
                new ProviderPreset("VOLC_SPEECH", "火山语音 (豆包TTS/ASR)", "https://openspeech.bytedance.com/api/v3", "speech-t2h-pro-char-50k"),
                new ProviderPreset("MINIMAX", "MiniMax 语音", "https://api.minimax.chat/v1", "speech-01-turbo-hd"),
                new ProviderPreset("CUSTOM", "自定义", "", "")));
        map.put(ApiProfileType.VIDEO, List.of(
                new ProviderPreset("SEEDANCE", "Seedance 生视频 (火山)", "https://ark.cn-beijing.volces.com/api/v3", "seedance-1-0-pro-250728"),
                new ProviderPreset("KLING", "可灵 Kling (快手)", "https://api.klingai.com/v1", "kling-v1"),
                new ProviderPreset("WANX_VIDEO", "万相视频 (阿里通义)", "https://dashscope.aliyuncs.com/compatible-mode/v1", "wanx2.1-v2v-turbo"),
                new ProviderPreset("CUSTOM", "自定义", "", "")));
        // 天气的"厂商"其实就和风一家，列表保留自定义（用户填 API Host / Key / 城市）
        map.put(ApiProfileType.WEATHER, List.of(
                new ProviderPreset("QWEATHER", "和风天气", "", ""),
                new ProviderPreset("CUSTOM", "自定义", "", "")));
        return map;
    }

    /**
     * 保存大模型配置到数据库。
     */
    @Transactional
    public LlmConfigDTO saveLlmConfig(LlmConfigDTO dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            SystemConfig cfg = repo.findByKey(LLM_CONFIG_KEY)
                    .orElseGet(() -> new SystemConfig(LLM_CONFIG_KEY, json));
            cfg.setValue(json);
            cfg.setUpdatedAt(java.time.LocalDateTime.now());
            repo.save(cfg);
            return dto;
        } catch (Exception e) {
            throw new RuntimeException("保存配置失败：" + e.getMessage(), e);
        }
    }

    /**
     * 提供商预设：用于前端下拉选择时自动填充 baseUrl 和默认 model。
     */
    public record ProviderPreset(String code, String name, String baseUrl, String defaultModel) {
    }

    // === 天气配置 ===

    public org.example.duobaan.model.dto.WeatherConfigDTO getWeatherConfig() {
        Optional<SystemConfig> saved = repo.findByKey(WEATHER_CONFIG_KEY);
        DuobaanProperties.Weather w = props.getWeather();
        if (saved.isPresent()) {
            try {
                org.example.duobaan.model.dto.WeatherConfigDTO dto = objectMapper.readValue(
                        saved.get().getValue(), org.example.duobaan.model.dto.WeatherConfigDTO.class);
                // 向后兼容：旧配置 JSON 缺少 apiHost/provider/location 时，用 properties 补齐
                String provider = (dto.provider() == null || dto.provider().isEmpty())
                        ? w.getProvider() : dto.provider();
                String apiHost = (dto.apiHost() == null || apiHostEmpty(dto)) ? w.getApiHost() : dto.apiHost();
                String apiKey = dto.apiKey() == null ? w.getApiKey() : dto.apiKey();
                String location = (dto.location() == null || dto.location().isEmpty())
                        ? w.getLocation() : dto.location();
                long ttl = dto.cacheTtlSeconds() <= 0 ? w.getCacheTtlSeconds() : dto.cacheTtlSeconds();
                return new org.example.duobaan.model.dto.WeatherConfigDTO(
                        provider, apiHost, apiKey, location, ttl);
            } catch (Exception ignore) {
            }
        }
        return new org.example.duobaan.model.dto.WeatherConfigDTO(
                w.getProvider(), w.getApiHost(), w.getApiKey(), w.getLocation(), w.getCacheTtlSeconds());
    }

    // Jackson 反序列化 record 时，若原始 JSON 字段缺失则按 0 参填；这里兼容 apiHost 可能为 null/空串
    private static boolean apiHostEmpty(org.example.duobaan.model.dto.WeatherConfigDTO dto) {
        String h = dto.apiHost();
        return h == null || h.isBlank() || "null".equalsIgnoreCase(h);
    }

    @Transactional
    public org.example.duobaan.model.dto.WeatherConfigDTO saveWeatherConfig(
            org.example.duobaan.model.dto.WeatherConfigDTO dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            SystemConfig cfg = repo.findByKey(WEATHER_CONFIG_KEY)
                    .orElseGet(() -> new SystemConfig(WEATHER_CONFIG_KEY, json));
            cfg.setValue(json);
            cfg.setUpdatedAt(java.time.LocalDateTime.now());
            repo.save(cfg);
            return dto;
        } catch (Exception e) {
            throw new RuntimeException("保存天气配置失败：" + e.getMessage(), e);
        }
    }
}
