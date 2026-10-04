package org.example.duobaan.controller;

import java.util.List;

import org.example.duobaan.model.dto.ChunkDTO;
import org.example.duobaan.model.dto.KbAddResult;
import org.example.duobaan.model.dto.KbDTO;
import org.example.duobaan.model.dto.RetrievedChunk;
import org.example.duobaan.service.EmbeddingService;
import org.example.duobaan.service.KnowledgeService;
import org.example.duobaan.service.RedisVectorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库接口：RAG 的知识库管理 + 检索调试。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final EmbeddingService embeddingService;
    private final RedisVectorService redisVectorService;

    public KnowledgeController(KnowledgeService knowledgeService,
                               EmbeddingService embeddingService,
                               RedisVectorService redisVectorService) {
        this.knowledgeService = knowledgeService;
        this.embeddingService = embeddingService;
        this.redisVectorService = redisVectorService;
    }

    record KbCreate(String name, String description,
                    Integer chunkSize, Integer chunkOverlap, String splitStrategy) {}
    record DocIn(String text) {}
    record SearchIn(String query) {}

    /** RAG 运行状态：向量化就绪 + Redis 连通 */
    public record RagStatus(boolean embeddingReady, boolean redisOk, String hint) {}

    // ====================== 知识库 ======================

    @GetMapping("/bases")
    public List<KbDTO> listBases() {
        return knowledgeService.listBases();
    }

    @PostMapping("/bases")
    public KbDTO createBase(@RequestBody KbCreate req) {
        return knowledgeService.createBase(req.name(), req.description(),
                req.chunkSize(), req.chunkOverlap(), req.splitStrategy());
    }

    @DeleteMapping("/bases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBase(@PathVariable Long id) {
        knowledgeService.deleteBase(id);
    }

    // ====================== 文档 / 分块 ======================

    @PostMapping("/bases/{id}/documents")
    public KbAddResult addDocument(@PathVariable Long id, @RequestBody DocIn req) {
        return knowledgeService.addDocument(id, req.text());
    }

    @PostMapping(value = "/bases/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KbAddResult uploadDocument(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return knowledgeService.uploadDocument(id, file);
    }

    @GetMapping("/bases/{id}/chunks")
    public List<ChunkDTO> listChunks(@PathVariable Long id) {
        return knowledgeService.listChunks(id);
    }

    @DeleteMapping("/bases/{id}/chunks")
    public int clearChunks(@PathVariable Long id) {
        return knowledgeService.clearChunks(id);
    }

    /** 对已有分块重新向量化（配置 embedding 后补做向量） */
    @PostMapping("/bases/{id}/revectorize")
    public int revectorize(@PathVariable Long id) {
        return knowledgeService.revectorize(id);
    }

    // ====================== 检索调试 ======================

    @PostMapping("/search")
    public List<RetrievedChunk> search(@RequestBody SearchIn req) {
        return knowledgeService.search(req.query(), 4);
    }

    // ====================== RAG 状态 ======================

    @GetMapping("/status")
    public RagStatus status() {
        boolean embReady = embeddingService.isReady();
        boolean redisOk = redisVectorService.ping();
        String hint;
        if (!embReady && !redisOk) {
            hint = "请先在「设置 → 向量化模型」配置 embedding，并启动本地 Redis。";
        } else if (!embReady) {
            hint = "请先在「设置 → 向量化模型」配置 embedding 模型（独立的 Base URL / Key / 模型 ID）。";
        } else if (!redisOk) {
            hint = "Redis 未连通，请检查本地 Redis 是否启动（localhost:6379）。";
        } else {
            hint = "知识库检索就绪。";
        }
        return new RagStatus(embReady, redisOk, hint);
    }
}
