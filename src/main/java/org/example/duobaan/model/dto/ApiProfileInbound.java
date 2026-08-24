package org.example.duobaan.model.dto;

import org.example.duobaan.model.ApiProfileType;

/**
 * 前端入参：创建/更新时使用。
 * apiKey：创建时必填；更新时若为空字符串/null 表示不修改原 Key。
 */
public record ApiProfileInbound(
        ApiProfileType profileType,
        String name,
        String provider,
        String baseUrl,
        String model,
        String apiKey,          // 明文，仅在入库时写库；任何返回体不回显
        String location,
        Long cacheTtlSeconds,
        Integer timeoutSeconds,
        boolean setActive       // 仅在 create/update/save-as-new 提交时有效
) {
}
