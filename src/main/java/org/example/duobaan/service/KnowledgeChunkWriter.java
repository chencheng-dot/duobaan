package org.example.duobaan.service;

import java.time.LocalDateTime;
import java.util.List;

import org.example.duobaan.model.KnowledgeBase;
import org.example.duobaan.model.KnowledgeChunk;
import org.example.duobaan.model.dto.KbAddResult;
import org.example.duobaan.repository.KnowledgeBaseRepository;
import org.example.duobaan.repository.KnowledgeChunkRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识库分块写入器（独立 bean，承载真正生效的事务边界）。
 *
 * 为什么从 KnowledgeService 拆出来：
 * Spring 的 @Transactional 基于代理，同一个 bean 内部的 this.addDocument(...) 属于自调用，
 * 不经过代理，注解等于没有事务。表现就是「知识库建好了、description 的分块只写进去一半、
 * 用户看到创建成功」的半成品数据。拆成独立 bean 后由容器注入代理，跨 bean 调用事务才真正生效。
 *
 * 传播行为为默认 REQUIRED：
 *   - 由控制器直接调用（KnowledgeController.addDocument）→ 事务在本层开启并提交；
 *   - 由 KnowledgeService.uploadDocument(@Transactional) 内部调用 → 并入外层事务，同生共死。
 * 注意：createBase 刻意不加 @Transactional，这样 writer 用自己的事务独立提交，
 * 才能做到「知识库本身保留 + 描述分块整体回滚（要么全有要么全无）」。
 */
@Service
public class KnowledgeChunkWriter {

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final EmbeddingService embeddingService;
    private final RedisVectorService redisVectorService;

    public KnowledgeChunkWriter(KnowledgeBaseRepository kbRepo,
                                KnowledgeChunkRepository chunkRepo,
                                EmbeddingService embeddingService,
                                RedisVectorService redisVectorService) {
        this.kbRepo = kbRepo;
        this.chunkRepo = chunkRepo;
        this.embeddingService = embeddingService;
        this.redisVectorService = redisVectorService;
    }

    /**
     * 写入一个文档：分块 → 向量化 → 分块存 MySQL → 向量存 Redis。
     * 整个过程在一个事务里：任何一步抛异常都整体回滚，不会留下半成品分块。
     */
    @Transactional
    public KbAddResult addDocument(Long kbId, String text) {
        KnowledgeBase kb = kbRepo.findById(kbId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在 id=" + kbId));
        List<String> chunks = KnowledgeService.chunk(text, kb.getSplitStrategy(),
                kb.getChunkSize() == null ? 500 : kb.getChunkSize(),
                kb.getChunkOverlap() == null ? 50 : kb.getChunkOverlap());
        if (chunks.isEmpty()) {
            return new KbAddResult(0, embeddingService.isReady());
        }
        List<float[]> vectors = embeddingService.isReady()
                ? embeddingService.embed(chunks)
                : List.of();

        int startIndex = (int) chunkRepo.countByKbId(kbId);
        int saved = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            if (c.isBlank()) {
                continue;
            }
            KnowledgeChunk kc = new KnowledgeChunk();
            kc.setKbId(kbId);
            kc.setContent(c);
            kc.setChunkIndex(startIndex + saved);
            kc.setCreatedAt(LocalDateTime.now());
            // embedding 列保留为空（向量存 Redis）
            kc.setEmbedding(null);
            KnowledgeChunk savedChunk = chunkRepo.save(kc);

            float[] v = (vectors != null && vectors.size() > i) ? vectors.get(i) : null;
            if (v != null) {
                redisVectorService.storeVector(kbId, savedChunk.getId(), v);
            }
            saved++;
        }
        return new KbAddResult(saved, embeddingService.isReady());
    }
}
