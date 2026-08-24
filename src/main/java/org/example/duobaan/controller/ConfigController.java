package org.example.duobaan.controller;

import java.util.List;

import org.example.duobaan.model.dto.LlmConfigDTO;
import org.example.duobaan.service.ConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统配置接口：大模型提供商配置的读取与保存。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /** 获取当前生效的大模型配置 */
    @GetMapping("/llm")
    public LlmConfigDTO getLlmConfig() {
        return configService.getLlmConfig();
    }

    /** 获取预设提供商列表 */
    @GetMapping("/providers")
    public List<ConfigService.ProviderPreset> getProviders() {
        return configService.getProviderPresets();
    }

    /** 保存大模型配置 */
    @PostMapping("/llm")
    public LlmConfigDTO saveLlmConfig(@RequestBody LlmConfigDTO dto) {
        return configService.saveLlmConfig(dto);
    }
}
