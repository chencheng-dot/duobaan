package org.example.duobaan.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.ApiProfile;
import org.example.duobaan.model.ApiProfileType;
import org.example.duobaan.model.SystemConfig;
import org.example.duobaan.model.dto.ApiProfileDTO;
import org.example.duobaan.model.dto.ApiProfileInbound;
import org.example.duobaan.model.dto.LlmConfigDTO;
import org.example.duobaan.model.dto.WeatherConfigDTO;
import org.example.duobaan.repository.ApiProfileRepository;
import org.example.duobaan.repository.SystemConfigRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

@Service
public class ApiProfileService {

    private static final String CFG_LLM = "llm.config";
    private static final String CFG_WEATHER = "weather.config";

    private final ApiProfileRepository repo;
    private final SystemConfigRepository systemConfigRepo;
    private final DuobaanProperties props;
    private final ObjectMapper objectMapper;

    public ApiProfileService(ApiProfileRepository repo, SystemConfigRepository systemConfigRepo,
                             DuobaanProperties props, ObjectMapper objectMapper) {
        this.repo = repo;
        this.systemConfigRepo = systemConfigRepo;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // =================== 对外 list/get（返回打码版） ===================

    public List<ApiProfileDTO> list(ApiProfileType type) {
        return repo.findByProfileTypeOrderByUpdatedAtDesc(type)
                .stream()
                .map(ApiProfileService::toMaskedDTO)
                .toList();
    }

    public Optional<ApiProfileDTO> get(Long id) {
        return repo.findById(id).map(ApiProfileService::toMaskedDTO);
    }

    public Optional<ApiProfileDTO> getActive(ApiProfileType type) {
        return repo.findByProfileTypeAndIsActiveTrue(type).map(ApiProfileService::toMaskedDTO);
    }

    // ================= 仅内部使用：返回带明文 Key 的对象（绝不对外传递） =================

    public Optional<ApiProfile> getActivePlain(ApiProfileType type) {
        return repo.findByProfileTypeAndIsActiveTrue(type);
    }

    // ========================= 写操作：create / update / setActive / delete ========================

    @Transactional
    public ApiProfileDTO create(ApiProfileInbound in) {
        validate(in, true);
        ApiProfile p = new ApiProfile();
        applyInbound(p, in, true);
        if (in.setActive()) {
            repo.clearActiveByType(p.getProfileType());
            p.setIsActive(true);
        } else {
            p.setIsActive(false);
        }
        return toMaskedDTO(repo.save(p));
    }

    @Transactional
    public ApiProfileDTO update(Long id, ApiProfileInbound in) {
        ApiProfile p = findOrThrow(id);
        // profileType 禁止从前端修改，但入参传的 type 若与实际不同且有值时保留 DB 原来的，绝不跨类型变
        validate(in, false);
        boolean changingActive = in.setActive() && !Boolean.TRUE.equals(p.getIsActive());
        if (changingActive) repo.clearActiveByType(p.getProfileType());
        applyInbound(p, in, false);
        if (changingActive) p.setIsActive(true);
        return toMaskedDTO(repo.save(p));
    }

    @Transactional
    public ApiProfileDTO setActive(Long id) {
        ApiProfile p = findOrThrow(id);
        repo.clearActiveByType(p.getProfileType());
        p.setIsActive(true);
        p.setUpdatedAt(LocalDateTime.now());
        return toMaskedDTO(repo.save(p));
    }

    @Transactional
    public void delete(Long id) {
        ApiProfile p = findOrThrow(id);
        repo.delete(p);
    }

    // ========================= 启动迁移：system_config 老 JSON → api_profile ========================

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrateLegacyConfigs() {
        try {
            migrateOne(ApiProfileType.LLM, CFG_LLM);
            migrateOne(ApiProfileType.WEATHER, CFG_WEATHER);
        } catch (Exception ignore) {
            // 迁移失败只打印日志，不阻断启动；旧 system_config 仍在，用户可手动重填
        }
    }

    private void migrateOne(ApiProfileType type, String cfgKey) {
        if (repo.countByProfileType(type) > 0) return;
        Optional<SystemConfig> saved = systemConfigRepo.findByKey(cfgKey);
        if (saved.isEmpty()) return;
        try {
            ApiProfile profile = new ApiProfile();
            profile.setProfileType(type);
            profile.setIsActive(true);
            if (type == ApiProfileType.LLM) {
                LlmConfigDTO dto = objectMapper.readValue(saved.get().getValue(), LlmConfigDTO.class);
                profile.setName("默认迁移-LLM");
                profile.setProvider(dto.provider());
                profile.setBaseUrl(dto.baseUrl());
                profile.setModel(dto.model());
                profile.setApiKey(or(dto.apiKey(), props.getLlm().getApiKey()));
                profile.setTimeoutSeconds(dto.timeoutSeconds() > 0 ? dto.timeoutSeconds()
                        : props.getLlm().getTimeoutSeconds());
            } else {
                WeatherConfigDTO dto = objectMapper.readValue(saved.get().getValue(), WeatherConfigDTO.class);
                profile.setName("默认迁移-WEATHER");
                profile.setProvider("qweather");
                String host = dto.apiHost() != null && !dto.apiHost().isBlank()
                        ? dto.apiHost() : props.getWeather().getApiHost();
                profile.setBaseUrl(host);
                String key = dto.apiKey() != null && !dto.apiKey().isBlank()
                        ? dto.apiKey() : props.getWeather().getApiKey();
                profile.setApiKey(key);
                String loc = dto.location() != null && !dto.location().isBlank()
                        ? dto.location() : props.getWeather().getLocation();
                profile.setLocation(loc);
                Long ttl = dto.cacheTtlSeconds() > 0 ? dto.cacheTtlSeconds() : props.getWeather().getCacheTtlSeconds();
                profile.setCacheTtlSeconds(ttl);
            }
            ApiProfile created = repo.save(profile);
            if (created != null) {
                systemConfigRepo.deleteById(cfgKey);
            }
        } catch (Exception ignore) {
            // 反序列化失败，老数据跳过
        }
    }

    // ================================= 内部辅助 ======================================

    private ApiProfile findOrThrow(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在 id=" + id));
    }

    private void validate(ApiProfileInbound in, boolean creating) {
        if (in == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (creating && in.profileType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "profileType 不能为空（LLM=文本 IMAGE=图 AUDIO=语音 VIDEO=视频 WEATHER=天气）");
        }
        if (in.name() == null || in.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写「配置名称」");
        }
        if (in.name().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置名称过长（≤100字）");
        }
        if (creating) {
            if (in.apiKey() == null || in.apiKey().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 API Key");
            }
        }
    }

    private void applyInbound(ApiProfile target, ApiProfileInbound in, boolean creating) {
        if (creating) target.setProfileType(in.profileType());
        target.setName(in.name().trim());
        target.setProvider(in.provider());
        target.setBaseUrl(trim(in.baseUrl()));
        target.setModel(trim(in.model()));
        if (creating) {
            target.setApiKey(in.apiKey());
        } else {
            if (in.apiKey() != null && !in.apiKey().isBlank()) target.setApiKey(in.apiKey());
        }
        target.setLocation(trim(in.location()));
        target.setCacheTtlSeconds(in.cacheTtlSeconds());
        target.setTimeoutSeconds(in.timeoutSeconds());
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String or(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    /**
     * 打码：只允许返回前 4 + **** + 后 4，最短兜底 ****。
     * 长度 < 8 时不给任何真实字符，直接返回 ****。
     */
    public static String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) return "";
        int len = apiKey.length();
        if (len < 8) return "****";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(len - 4);
    }

    private static ApiProfileDTO toMaskedDTO(ApiProfile p) {
        return new ApiProfileDTO(
                p.getId(),
                p.getProfileType(),
                p.getName(),
                p.getProvider(),
                p.getBaseUrl(),
                p.getModel(),
                maskKey(p.getApiKey()),
                p.getLocation(),
                p.getCacheTtlSeconds(),
                p.getTimeoutSeconds(),
                Boolean.TRUE.equals(p.getIsActive()),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
