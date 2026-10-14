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

import org.example.duobaan.util.UrlSanitizer;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@Service
public class ApiProfileService {

    private static final String CFG_LLM = "llm.config";
    private static final String CFG_WEATHER = "weather.config";

    private final ApiProfileRepository repo;
    private final SystemConfigRepository systemConfigRepo;
    private final DuobaanProperties props;
    private final ObjectMapper objectMapper;

    private static final Logger log = LoggerFactory.getLogger(ApiProfileService.class);

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
        return repo.findByProfileTypeAndIsActiveTrue(type)
                .map(ApiProfileService::cleanForUse);
    }

    /** 根据 ID 获取带明文 Key 的实体（仅后端内部使用） */
    public Optional<ApiProfile> getPlain(Long id) {
        return repo.findById(id).map(ApiProfileService::cleanForUse);
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
        } catch (Exception e) {
            // 迁移失败只打印日志，不阻断启动；旧 system_config 仍在，用户可手动重填
            log.warn("旧配置迁移失败（不阻断启动）：{}", e.getMessage(), e);
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
        } catch (Exception e) {
            // 反序列化失败，老数据跳过
            log.warn("迁移旧配置时反序列化失败，已跳过：{}", e.getMessage());
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
            if (isMaskedKey(in.apiKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "检测到打码后的 Key（形如 abcd****wxyz），请粘贴真实 API Key");
            }
        }
        if (in.timeoutSeconds() != null && (in.timeoutSeconds() < 1 || in.timeoutSeconds() > 600)) {
            // 第 4 批配套：改走 ResponseStatusException → handleStatus，才能保留这句面向用户的校验提示
            //（GlobalExceptionHandler 已把 IllegalArgumentException 统一收敛成固定文案）。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "超时时间需在 1~600 秒之间");
        }
    }

    private void applyInbound(ApiProfile target, ApiProfileInbound in, boolean creating) {
        if (creating) target.setProfileType(in.profileType());
        target.setName(trim(in.name()));
        target.setProvider(in.provider());
        target.setBaseUrl(UrlSanitizer.sanitizeBaseUrl(in.baseUrl()));
        target.setModel(trim(in.model()));
        if (creating) {
            target.setApiKey(trim(in.apiKey()));
        } else {
            // 编辑模式：留空 = 不换 Key；打码串（abcd****wxyz）同样视为「不修改」。
            // 前端列表/详情返回的永远是打码值，一旦被回填提交当真 Key 写库，
            // 真实 Key 就被覆盖，之后所有大模型/图片/语音/天气调用全部 401 且界面看不出原因。
            if (StringUtils.hasText(in.apiKey()) && !isMaskedKey(in.apiKey())) {
                target.setApiKey(trim(in.apiKey()));
            }
        }
        target.setLocation(trim(in.location()));
        target.setCacheTtlSeconds(in.cacheTtlSeconds());
        target.setTimeoutSeconds(in.timeoutSeconds());
    }

    /** 读路径兜底清洗：即便是老数据（脏 baseUrl/model），到业务层用之前仍强制 clean 一次，避免 Illegal scheme / 404 */
    private static ApiProfile cleanForUse(ApiProfile p) {
        if (p == null) return null;
        String cleanUrl = UrlSanitizer.sanitizeBaseUrl(p.getBaseUrl());
        if (cleanUrl != null && !cleanUrl.equals(p.getBaseUrl())) p.setBaseUrl(cleanUrl);
        String cleanModel = trim(p.getModel());
        if (cleanModel != null && !cleanModel.equals(p.getModel())) p.setModel(cleanModel);
        return p;
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

    /**
     * 判断入参是不是「打码后的 Key」——即 maskKey() 产出的 前4 + **** + 后4（或整串 *）。
     * 打码值绝不能写库：写进去等于把真 Key 覆盖成一个假的字符串，
     * 之后所有大模型 / 图片 / 语音 / 天气调用都会 401，而界面上只显示「调用失败」。
     * 真实 API Key 不会包含 "****"，因此这个判定不会误伤。
     */
    static boolean isMaskedKey(String apiKey) {
        if (apiKey == null) {
            return false;
        }
        String k = apiKey.trim();
        if (k.isEmpty()) {
            return false;
        }
        if (k.contains("****")) {
            return true;
        }
        for (int i = 0; i < k.length(); i++) {
            if (k.charAt(i) != '*') {
                return false;
            }
        }
        return true;
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
