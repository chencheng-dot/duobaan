package org.example.duobaan.model.dto;

import java.util.List;

import org.example.duobaan.model.DiningType;

/**
 * 多巴胺餐食推荐输入：心情 / 天气 / 口味 / 用餐方式。
 */
public record MealContext(
        String mood,
        String weather,
        List<String> tasteTags,
        DiningType diningType) {

    public String summary() {
        return "心情:" + mood + " 天气:" + weather
                + " 口味:" + String.join("/", tasteTags)
                + " 用餐方式:" + (diningType == null ? "不限" : diningType.name());
    }
}
