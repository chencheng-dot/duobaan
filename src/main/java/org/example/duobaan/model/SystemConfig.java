package org.example.duobaan.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 系统配置表：存放大模型提供商等可运行时修改的配置。
 * 采用 key-value 结构，便于扩展。
 */
@Entity
@Table(name = "system_config")
public class SystemConfig {

    @Id
    @Column(name = "cfg_key", nullable = false, length = 100)
    private String key;

    @Lob
    @Column(name = "cfg_value", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private java.time.LocalDateTime updatedAt;

    public SystemConfig() {
    }

    public SystemConfig(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = java.time.LocalDateTime.now();
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = java.time.LocalDateTime.now();
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
