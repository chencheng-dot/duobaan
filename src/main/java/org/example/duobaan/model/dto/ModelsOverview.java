package org.example.duobaan.model.dto;

import java.util.List;

/**
 * 设置页 / 聊天页的「模型总览」响应。
 *
 * <p>把原本按 Tab 分六次拉的配置聚合成一次请求：
 * <ul>
 *   <li>{@code ai} —— 文本 / 图片 / 语音 / 视频 / 向量化，聊天页下拉按此分能力展示；</li>
 *   <li>{@code weather} —— 天气模型单独一块，不参与聊天页的能力路由。</li>
 * </ul>
 * 所有 Key 均为打码版（见 {@link ApiProfileDTO#apiKeyMasked()}），明文永不出后端。
 *
 * @param ai      AI 能力配置列表（不可为 null；入参为 null 时归一为空列表）
 * @param weather 天气模型配置列表（不可为 null；入参为 null 时归一为空列表）
 */
public record ModelsOverview(
        List<ApiProfileDTO> ai,
        List<ApiProfileDTO> weather
) {

    /** 防御性拷贝：避免调用方后续改动原列表，导致返回体被意外篡改 */
    public ModelsOverview {
        ai = ai == null ? List.of() : List.copyOf(ai);
        weather = weather == null ? List.of() : List.copyOf(weather);
    }
}
