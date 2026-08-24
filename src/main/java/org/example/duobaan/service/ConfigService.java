package org.example.duobaan.service;

import java.util.List;
import java.util.Optional;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.SystemConfig;
import org.example.duobaan.model.dto.LlmConfigDTO;
import org.example.duobaan.repository.SystemConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * 系统配置服务：负责大模型提供商等配置的持久化与读取。
 * 优先级：数据库配置 > application.properties 默认值。
 */
@Service
public class ConfigService {

    private static final String LLM_CONFIG_KEY = "llm.config";

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
     * 获取预设提供商列表（前端下拉选择用）。
     */
    public List<ProviderPreset> getProviderPresets() {
        return List.of(
                new ProviderPreset("CHATGPT", "ChatGPT", "https://api.openai.com/v1", "gpt-4o-mini"),
                new ProviderPreset("DEEPSEEK", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
                new ProviderPreset("DOUBAO", "豆包", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k"),
                new ProviderPreset("QIANWEN", "千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
                new ProviderPreset("CUSTOM", "自定义", "", ""));
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
}
