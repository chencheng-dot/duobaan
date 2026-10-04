package org.example.duobaan.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 知识库：RAG 检索的知识库集合。
 * 一个知识库下挂多个 knowledge_chunk 分块。
 */
@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    /** 分块目标长度（字符数） */
    @Column(name = "chunk_size", nullable = false)
    private Integer chunkSize = 500;

    /** 分块重叠长度（字符数），让相邻分块有上下文重叠 */
    @Column(name = "chunk_overlap", nullable = false)
    private Integer chunkOverlap = 50;

    /** 分块策略：PARAGRAPH=按段落 SENTENCE=按句子 MARKDOWN=按标题 SIMPLE=固定长度 */
    @Column(name = "split_strategy", nullable = false, length = 20)
    private String splitStrategy = "PARAGRAPH";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public KnowledgeBase() {
    }

    public KnowledgeBase(String name, String description) {
        this.name = name;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    public KnowledgeBase(String name, String description, Integer chunkSize, Integer chunkOverlap, String splitStrategy) {
        this.name = name;
        this.description = description;
        this.chunkSize = (chunkSize == null || chunkSize < 50) ? 500 : chunkSize;
        this.chunkOverlap = (chunkOverlap == null || chunkOverlap < 0) ? 50 : chunkOverlap;
        this.splitStrategy = (splitStrategy == null || splitStrategy.isBlank()) ? "PARAGRAPH" : splitStrategy;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public String getSplitStrategy() { return splitStrategy; }
    public void setSplitStrategy(String splitStrategy) { this.splitStrategy = splitStrategy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}