package org.example.duobaan.controller;

import java.util.List;

import org.example.duobaan.model.dto.LlmConfigDTO;
import org.example.duobaan.model.dto.WeatherConfigDTO;
import org.example.duobaan.service.ConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统配置接口：大模型、天气等运行时配置的读取与保存。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    // === 大模型 ===
    @GetMapping("/llm")
    public LlmConfigDTO getLlmConfig() {
        return configService.getLlmConfig();
    }

    @GetMapping("/providers")
    public List<ConfigService.ProviderPreset> getProviders() {
        return configService.getProviderPresets();
    }

    @PostMapping("/llm")
    public LlmConfigDTO saveLlmConfig(@RequestBody LlmConfigDTO dto) {
        return configService.saveLlmConfig(dto);
    }

    // === 天气 ===
    @GetMapping("/weather")
    public WeatherConfigDTO getWeatherConfig() {
        return configService.getWeatherConfig();
    }

    @PostMapping("/weather")
    public WeatherConfigDTO saveWeatherConfig(@RequestBody WeatherConfigDTO dto) {
        return configService.saveWeatherConfig(dto);
    }
}
