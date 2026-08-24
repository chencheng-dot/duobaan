package org.example.duobaan.controller;

import org.example.duobaan.model.dto.WeatherNow;
import org.example.duobaan.service.WeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时天气接口（后端带缓存）。
 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/now")
    public WeatherNow now() {
        return weatherService.now();
    }
}
