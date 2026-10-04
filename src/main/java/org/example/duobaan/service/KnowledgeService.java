package org.example.duobaan.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.duobaan.model.KnowledgeBase;
import org.example.duobaan.model.KnowledgeChunk;
import org.example.duobaan.model.dto.ChunkDTO;
import org.example.duobaan.model.dto.KbAddResult;
import org.example.duobaan.model.dto.KbDTO;
import org.example.duobaan.model.dto.RetrievedChunk;
import org.example.duobaan.repository.KnowledgeBaseRepository;
import org.example.duobaan.repository.KnowledgeChunkRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StringUtils;

/**
 * 知识库服务：知识库 / 分块的 CRUD + 文档分块向量化入库 + 基于余弦相似度的检索。
 *
 * 分块策略（知识库级别可配置）：
 *   - PARAGRAPH：按段落聚合，接近 chunkSize 时切分（默认）
 *   - SENTENCE ：按句子（。！？.!?）切分，再按 chunkSize 聚合
 *   - MARKDOWN ：按 Markdown 标题（# ## ###）切分
 *   - SIMPLE   ：固定长度硬切，带 chunkOverlap 重叠
 *
 * 向量存储：分块文本存 MySQL（knowledge_chunk），向量存 Redis（RedisVectorService）。
 * 检索：从 Redis 取向量在内存算余弦相似度，top-K 且 ≥ 阈值的注入 LLM。
 */
@Service
public class KnowledgeService {

    /** 召回条数上限 */
    private static final int TOP_K = 4;
    /** 余弦相似度阈值，低于该值视为无关，不注入 LLM */
    private static final double MIN_SCORE = 0.35;

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final EmbeddingService embeddingService;
    private final RedisVectorService redisVectorService;

    public KnowledgeService(KnowledgeBaseRepository kbRepo,
                            KnowledgeChunkRepository chunkRepo,
                            EmbeddingService embeddingService,
                            RedisVectorService redisVectorService) {
        this.kbRepo = kbRepo;
        this.chunkRepo = chunkRepo;
        this.embeddingService = embeddingService;
        this.redisVectorService = redisVectorService;
    }

    // ====================== 知识库 CRUD ======================

    public List<KbDTO> listBases() {
        return kbRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(kb -> new KbDTO(kb.getId(), kb.getName(), kb.getDescription(),
                        (int) chunkRepo.countByKbId(kb.getId()),
                        kb.getChunkSize() == null ? 500 : kb.getChunkSize(),
                        kb.getChunkOverlap() == null ? 50 : kb.getChunkOverlap(),
                        kb.getSplitStrategy() == null ? "PARAGRAPH" : kb.getSplitStrategy(),
                        kb.getCreatedAt()))
                .toList();
    }

    public KbDTO createBase(String name, String description, Integer chunkSize,
                            Integer chunkOverlap, String splitStrategy) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写知识库名称");
        }
        KnowledgeBase kb = kbRepo.save(new KnowledgeBase(
                name.trim(), trim(description), chunkSize, chunkOverlap, splitStrategy));
        // 若创建时填了描述，自动把描述当内容分块入库（用户常把资料直接写在描述里）
        int chunkCount = 0;
        if (description != null && !description.isBlank()) {
            try {
                chunkCount = addDocument(kb.getId(), description).chunkCount();
            } catch (Exception ignore) {
                // 分块失败不阻塞知识库创建
            }
        }
        return toDTO(kb, chunkCount);
    }

    @Transactional
    public void deleteBase(Long id) {
        findOrThrow(id);
        // 先删 MySQL 分块，再清 Redis 向量
        chunkRepo.deleteByKbId(id);
        redisVectorService.deleteVectorsByKb(id);
        kbRepo.deleteById(id);
    }

    // ====================== 文档 / 分块 ======================

    @Transactional
    public KbAddResult addDocument(Long kbId, String text) {
        KnowledgeBase kb = findOrThrow(kbId);
        List<String> chunks = chunk(text, kb.getSplitStrategy(),
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

    @Transactional
    public KbAddResult uploadDocument(Long kbId, MultipartFile file) {
        findOrThrow(kbId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要上传的文件");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!(filename.endsWith(".txt") || filename.endsWith(".md"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 .txt / .md 文件");
        }
        try {
            String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return addDocument(kbId, content);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取文件失败：" + e.getMessage());
        }
    }

    public List<ChunkDTO> listChunks(Long kbId) {
        findOrThrow(kbId);
        return chunkRepo.findByKbIdOrderByChunkIndexAsc(kbId).stream()
                .map(c -> new ChunkDTO(c.getId(), c.getKbId(), c.getChunkIndex(),
                        c.getContent(), c.getContent() == null ? 0 : c.getContent().length()))
                .toList();
    }

    @Transactional
    public int clearChunks(Long kbId) {
        findOrThrow(kbId);
        redisVectorService.deleteVectorsByKb(kbId);
        long removed = chunkRepo.deleteByKbId(kbId);
        return (int) removed;
    }

    /**
     * 对已有分块重新向量化：清掉 Redis 里该知识库的旧向量，重新调 embedding 接口写入。
     * 用于：上传时未配置 embedding、或换了 embedding 模型后需要重建向量的场景。
     * 返回成功向量化的分块数。
     */
    public int revectorize(Long kbId) {
        findOrThrow(kbId);
        if (!embeddingService.isReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "向量化模型未就绪，请先在「设置 → 向量化模型」配置 embedding。");
        }
        List<KnowledgeChunk> chunks = chunkRepo.findByKbIdOrderByChunkIndexAsc(kbId);
        if (chunks.isEmpty()) {
            return 0;
        }
        // 先清旧向量
        redisVectorService.deleteVectorsByKb(kbId);

        // 分批向量化（每批 16 条，避免单次请求过大）
        int done = 0;
        int batchSize = 16;
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            List<KnowledgeChunk> batch = chunks.subList(start, end);
            List<String> texts = batch.stream().map(KnowledgeChunk::getContent).toList();
            List<float[]> vectors = embeddingService.embed(texts);
            for (int i = 0; i < batch.size(); i++) {
                float[] v = (vectors != null && vectors.size() > i) ? vectors.get(i) : null;
                if (v != null) {
                    redisVectorService.storeVector(kbId, batch.get(i).getId(), v);
                    done++;
                }
            }
        }
        return done;
    }

    // ====================== 检索 ======================

    /**
     * 检索：向量化 query 后，从 Redis 取所有知识库的分块向量，算余弦相似度，
     * 取 top-K 且得分 ≥ 阈值的分块。向量化不可用时返回空列表。
     */
    public List<RetrievedChunk> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        float[] qv = embeddingService.embedOne(query.trim());
        if (qv == null) {
            return List.of();
        }
        // 取所有有向量的知识库 ID
        List<Long> kbIds = new ArrayList<>(redisVectorService.getAllKbIds());
        if (kbIds.isEmpty()) {
            return List.of();
        }
        // 知识库 id → 名称
        Map<Long, String> kbNames = new HashMap<>();
        kbRepo.findAll().forEach(kb -> kbNames.put(kb.getId(), kb.getName()));

        // 收集所有候选 chunkId
        List<Long> allChunkIds = new ArrayList<>();
        for (Long kbId : kbIds) {
            allChunkIds.addAll(redisVectorService.getChunkIds(kbId));
        }
        if (allChunkIds.isEmpty()) {
            return List.of();
        }
        // 批量取向量
        List<float[]> vectors = redisVectorService.getVectors(allChunkIds);
        // chunkId → kbId 映射
        Map<Long, Long> chunkToKb = new HashMap<>();
        for (Long kbId : kbIds) {
            for (Long cid : redisVectorService.getChunkIds(kbId)) {
                chunkToKb.put(cid, kbId);
            }
        }
        // 批量取分块内容（用 findAllById）
        Map<Long, String> contents = new HashMap<>();
        chunkRepo.findAllById(allChunkIds).forEach(c -> contents.put(c.getId(), c.getContent()));

        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < allChunkIds.size(); i++) {
            float[] cv = vectors.get(i);
            if (cv == null) continue;
            double s = cosine(qv, cv);
            if (s >= MIN_SCORE) {
                Long cid = allChunkIds.get(i);
                scored.add(new ScoredChunk(
                        cid,
                        chunkToKb.get(cid),
                        contents.get(cid),
                        s));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int n = Math.max(1, Math.min(topK, TOP_K));
        List<RetrievedChunk> out = new ArrayList<>();
        for (ScoredChunk sc : scored) {
            if (out.size() >= n) break;
            out.add(new RetrievedChunk(
                    sc.kbId,
                    kbNames.getOrDefault(sc.kbId, "知识库"),
                    sc.chunkId,
                    sc.content,
                    sc.score));
        }
        return out;
    }

    /**
     * 拼装注入到 LLM 系统提示词的知识库上下文。
     * 无命中返回 null（调用方不加任何 RAG 提示）。
     */
    public String retrieveContext(String query) {
        List<RetrievedChunk> hits = search(query, TOP_K);
        if (hits.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是知识库中与用户问题相关的参考资料，请优先依据这些内容回答；若与问题无关请忽略：\n");
        int i = 1;
        for (RetrievedChunk h : hits) {
            sb.append("[").append(i++).append("]（知识库「").append(h.kbName()).append("」）：")
                    .append(h.content()).append("\n");
        }
        return sb.toString();
    }

    // ====================== 分块策略 ======================

    /**
     * 按策略分块。
     * @param strategy PARAGRAPH / SENTENCE / MARKDOWN / SIMPLE
     */
    static List<String> chunk(String text, String strategy, int chunkSize, int chunkOverlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = chunkSize < 50 ? 500 : chunkSize;
        int overlap = chunkOverlap < 0 ? 0 : Math.min(chunkOverlap, size / 2);
        String s = (strategy == null) ? "PARAGRAPH" : strategy.trim().toUpperCase();
        return switch (s) {
            case "SENTENCE" -> chunkBySentence(text, size, overlap);
            case "MARKDOWN" -> chunkByMarkdown(text, size);
            case "SIMPLE" -> chunkSimple(text, size, overlap);
            default -> chunkByParagraph(text, size, overlap);
        };
    }

    /** 按段落聚合，接近 size 时切分，相邻分块取 overlap 字符重叠 */
    private static List<String> chunkByParagraph(String text, int size, int overlap) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] paragraphs = normalized.split("\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String raw : paragraphs) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            while (p.length() > size) {
                if (cur.length() > 0) {
                    chunks.add(cur.toString());
                    cur.setLength(0);
                }
                chunks.add(p.substring(0, size));
                p = p.substring(size);
            }
            if (cur.length() + p.length() + 1 > size) {
                if (cur.length() > 0) {
                    chunks.add(applyOverlap(cur.toString().trim(), chunks, overlap));
                    cur.setLength(0);
                }
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(p);
        }
        if (cur.length() > 0) {
            chunks.add(applyOverlap(cur.toString().trim(), chunks, overlap));
        }
        return chunks.stream().filter(StringUtils::hasText).toList();
    }

    /** 按句子切分，再按 size 聚合成块 */
    private static List<String> chunkBySentence(String text, int size, int overlap) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        // 按中英文句末标点切分
        String[] sentences = normalized.split("(?<=[。！？.!?])\\s*");
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sen : sentences) {
            String s = sen.trim();
            if (s.isEmpty()) continue;
            if (cur.length() + s.length() > size && cur.length() > 0) {
                chunks.add(applyOverlap(cur.toString().trim(), chunks, overlap));
                cur.setLength(0);
            }
            cur.append(s);
        }
        if (cur.length() > 0) {
            chunks.add(applyOverlap(cur.toString().trim(), chunks, overlap));
        }
        return chunks.stream().filter(StringUtils::hasText).toList();
    }

    /** 按 Markdown 标题（# ## ###）切分，单块过长时按段落再切 */
    private static List<String> chunkByMarkdown(String text, int size) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("(?m)^(?=#{1,6}\\s)");
        List<String> chunks = new ArrayList<>();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.length() <= size) {
                chunks.add(p);
            } else {
                // 单个标题块过长，按段落再切
                chunks.addAll(chunkByParagraph(p, size, 0));
            }
        }
        return chunks.stream().filter(StringUtils::hasText).toList();
    }

    /** 固定长度硬切，带 overlap 重叠 */
    private static List<String> chunkSimple(String text, int size, int overlap) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> chunks = new ArrayList<>();
        int step = Math.max(1, size - overlap);
        for (int i = 0; i < t.length(); i += step) {
            int end = Math.min(i + size, t.length());
            chunks.add(t.substring(i, end));
            if (end >= t.length()) break;
        }
        return chunks;
    }

    /** 给当前块追加与上一块末尾 overlap 字符的重叠（只加在前缀，避免重复整段） */
    private static String applyOverlap(String current, List<String> prev, int overlap) {
        if (overlap <= 0 || prev.isEmpty()) return current;
        String last = prev.get(prev.size() - 1);
        if (last.length() <= overlap) return current;
        return last.substring(last.length() - overlap) + current;
    }

    /** 余弦相似度；维度不一致或零向量返回 0 */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ====================== 内部辅助 ======================

    private KnowledgeBase findOrThrow(Long id) {
        return kbRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在 id=" + id));
    }

    private KbDTO toDTO(KnowledgeBase kb, int count) {
        return new KbDTO(kb.getId(), kb.getName(), kb.getDescription(), count,
                kb.getChunkSize() == null ? 500 : kb.getChunkSize(),
                kb.getChunkOverlap() == null ? 50 : kb.getChunkOverlap(),
                kb.getSplitStrategy() == null ? "PARAGRAPH" : kb.getSplitStrategy(),
                kb.getCreatedAt());
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private record ScoredChunk(Long chunkId, Long kbId, String content, double score) {
    }
}
