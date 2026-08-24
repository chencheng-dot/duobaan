package org.example.duobaan.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 多套 API 配置持久化：LLM & 天气 共用一张表。
 * 安全设计：api_key 仅在 save/update 请求时从前端明文入参写库；
 * 任何 list/get 接口只返回打码版；删除 = 物理 DELETE 行（不留残 Key）。
 */
@Entity
@Table(name = "api_profile", indexes = {
        @Index(name = "idx_api_profile_type_active", columnList = "profile_type, is_active"),
        @Index(name = "idx_api_profile_type_updated", columnList = "profile_type, updated_at DESC")
})
public class ApiProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_type", nullable = false, length = 20)
    private ApiProfileType profileType;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String provider;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(length = 100)
    private String model;

    @Lob
    @Column(name = "api_key", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String apiKey;

    @Column(length = 100)
    private String location;

    @Column(name = "cache_ttl_seconds")
    private Long cacheTtlSeconds;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    @Column(name = "is_active", columnDefinition = "TINYINT(1) DEFAULT 0")
    private Boolean isActive = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ApiProfile() {
    }

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ApiProfileType getProfileType() { return profileType; }
    public void setProfileType(ApiProfileType profileType) { this.profileType = profileType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Long getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(Long cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
