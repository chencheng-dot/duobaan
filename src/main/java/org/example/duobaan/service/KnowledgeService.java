package org.example.duobaan.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.example.duobaan.model.KnowledgeBase;
import org.example.duobaan.model.KnowledgeChunk;
import org.example.duobaan.model.dto.ChunkDTO;
import org.example.duobaan.model.dto.KbAddResult;
import org.example.duobaan.model.dto.KbDTO;
import org.example.duobaan.model.dto.RagStatus;
import org.example.duobaan.model.dto.RetrievedChunk;
import org.example.duobaan.model.dto.SearchExplain;
import org.example.duobaan.repository.KnowledgeBaseRepository;
import org.example.duobaan.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /** 召回条数上限 */
    private static final int TOP_K = 4;
    /** 余弦相似度阈值，低于该值视为无关，不注入 LLM */
    private static final double MIN_SCORE = 0.35;
    /**
     * 单个知识库文档体积上限（10MB）：multipart 全局上限之外再自查一道，避免整体读进内存。
     * 控制器复用同一常量做入口校验，避免「接口层一套阈值、服务层另一套阈值」两处漂移
     * （曾出现把检索词上限 2000 字符误用于文档正文，导致正常长文被 400 拒绝的缺陷）。
     */
    public static final long MAX_DOC_BYTES = 10L * 1024 * 1024;
    /**
     * 单次「重建向量」允许的最大分块数。
     * 重建采用「先全部算完、再统一落盘」的两阶段方式（避免先清后失败把索引清空），
     * 全部向量会短暂驻留内存，因此必须有上界，超过时要求分批处理。
     */
    static final int MAX_REVECTORIZE_CHUNKS = 20_000;
    /** 重建向量分批大小（单次 embedding 请求最多多少段文本） */
    private static final int REVECTORIZE_BATCH_SIZE = 16;

    private final KnowledgeBaseRepository kbRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final EmbeddingService embeddingService;
    private final RedisVectorService redisVectorService;
    private final KnowledgeChunkWriter chunkWriter;

    /** 按知识库维度的重建锁：同一知识库的并发「重建向量」串行化，避免互相覆盖索引 */
    private final ConcurrentHashMap<Long, ReentrantLock> revectorizeLocks = new ConcurrentHashMap<>();

    public KnowledgeService(KnowledgeBaseRepository kbRepo,
                            KnowledgeChunkRepository chunkRepo,
                            EmbeddingService embeddingService,
                            RedisVectorService redisVectorService,
                            KnowledgeChunkWriter chunkWriter) {
        this.kbRepo = kbRepo;
        this.chunkRepo = chunkRepo;
        this.embeddingService = embeddingService;
        this.redisVectorService = redisVectorService;
        this.chunkWriter = chunkWriter;
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
                // 必须走独立 bean（KnowledgeChunkWriter），不能写成 this.addDocument(...)：
                // 自调用不经过代理，@Transactional 会失效，从而产生「半成品分块」。
                // createBase 刻意不加 @Transactional：writer 用自己的事务独立提交，
                // 库本身保留、描述分块整体回滚（要么全有要么全无），失败原因已记日志。
                chunkCount = chunkWriter.addDocument(kb.getId(), description).chunkCount();
            } catch (Exception e) {
                // 分块失败不阻塞知识库创建，但不再静默吞异常
                log.warn("知识库 {} 的初始描述分块失败：{}", kb.getId(), e.toString(), e);
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

    /**
     * 写入文档：对外签名（参数与返回类型 KbAddResult）保持不变，内部薄委托给独立 bean。
     * 事务边界在 KnowledgeChunkWriter.addDocument 上：跨 bean 调用经过代理，事务才真正生效；
     * 原来的 this.addDocument(...) 自调用不开启事务，正是「半成品分块」的根因。
     *
     * <p>入参在进入 writer 之前先校验：空内容直接 400，超限直接 413，
     * 避免「空文档也走一遍 embedding 调用」与「超大文本被整体读进内存」（fail-fast）。
     */
    public KbAddResult addDocument(Long kbId, String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档内容不能为空");
        }
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_DOC_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文档内容过大（上限 10MB），当前约 " + (bytes / 1024) + " KB");
        }
        return chunkWriter.addDocument(kbId, text);
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
        // 读取之前先卡体积，避免超大文件被整体读进内存（与全局 multipart 上限互为补充）
        if (file.getSize() > MAX_DOC_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件过大（≤10MB）：" + file.getOriginalFilename());
        }
        try {
            String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return addDocument(kbId, content);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // 第 4 批：原文只进日志，用户只看到可读文案（本分支最多泄露本地路径）
            log.warn("读取知识库文件失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取文件失败，请确认文件可访问。");
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
     * 对已有分块重新向量化：把全部分块的向量重算一遍后整体替换 Redis 里的旧索引。
     * 用于：上传时未配置 embedding、换了 embedding 模型、或 Redis 丢过数据的场景。
     *
     * <p>为什么是「两阶段」而不是「先清后算」：旧实现先 deleteVectorsByKb 再逐批调用
     * embedding，一旦额度耗尽（4xx），旧索引已经被清空、新索引又写不进去，
     * 结果是「点一次重建 = 把能用的索引也弄没了」。现在先把所有向量算完并校验完整，
     * 任何一批缺失都在动旧数据之前失败并保留原索引。
     *
     * <p>并发：同一知识库的重建用分段锁串行化（不同知识库互不影响），
     * 避免两个请求交错删除/写入产生半套索引。
     *
     * @return 实际写入的分块数；只有「全部分块都成功写入」才会返回，
     *         零写入不再被当作成功（历史缺陷：返回 0 且 HTTP 200，前端显示成功）
     */
    public int revectorize(Long kbId) {
        findOrThrow(kbId);
        if (!embeddingService.isReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "向量化模型未就绪，请先在「设置 → 向量化模型」配置 embedding。");
        }
        ReentrantLock lock = revectorizeLocks.computeIfAbsent(kbId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该知识库正在重建向量，请等待当前任务完成后再试。");
        }
        try {
            List<KnowledgeChunk> chunks = chunkRepo.findByKbIdOrderByChunkIndexAsc(kbId);
            if (chunks.isEmpty()) {
                return 0;
            }
            if (chunks.size() > MAX_REVECTORIZE_CHUNKS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "分块数过多（" + chunks.size() + "），单次重建上限 " + MAX_REVECTORIZE_CHUNKS
                                + "，请先减少分块（如清空后分批重新入库）。");
            }

            // 第一阶段：全部分块重算向量（不改动任何已有数据）
            List<float[]> vectors = new ArrayList<>(chunks.size());
            int missing = 0;
            for (int start = 0; start < chunks.size(); start += REVECTORIZE_BATCH_SIZE) {
                int end = Math.min(start + REVECTORIZE_BATCH_SIZE, chunks.size());
                List<String> texts = chunks.subList(start, end).stream().map(KnowledgeChunk::getContent).toList();
                List<float[]> batch = embeddingService.embed(texts);
                for (int i = 0; i < end - start; i++) {
                    float[] v = (batch != null && batch.size() > i) ? batch.get(i) : null;
                    if (v == null) {
                        missing++;
                    }
                    vectors.add(v);
                }
            }
            if (missing > 0) {
                // 不动旧索引：避免「重建失败 + 旧索引被清空」的双重损失
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "向量化未完成（" + missing + "/" + chunks.size() + " 个分块失败），"
                                + "已保留原有索引；" + embeddingService.failureSuffix());
            }

            // 第二阶段：整体替换（先算后写，写失败时旧索引会被清掉的部分才可感知）
            redisVectorService.deleteVectorsByKb(kbId);
            int done = 0;
            for (int i = 0; i < chunks.size(); i++) {
                if (redisVectorService.storeVector(kbId, chunks.get(i).getId(), vectors.get(i))) {
                    done++;
                }
            }
            if (done == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "向量写入失败（0/" + chunks.size() + "），Redis 可能不可用；请检查 Redis 后重试。");
            }
            if (done < chunks.size()) {
                log.warn("知识库 {} 重建向量部分失败：{}/{}", kbId, done, chunks.size());
            }
            return done;
        } finally {
            lock.unlock();
        }
    }

    // ====================== 状态自检 ======================

    /**
     * 知识库整体状态自检：分块数与向量数逐一比对，把「配置正常但索引为空」显式暴露为降级。
     *
     * <p>背景：线上曾出现「状态显示就绪、检索永远返回空、界面无任何提示」的静默失效。
     * 向量数量以 Redis 登记集合（SCARD）为准，是近似值：残留孤儿成员会被计入，需要「重建向量」清理。
     */
    public RagStatus status() {
        boolean embeddingReady = embeddingService.isReady();
        // 单独探测一次 Redis：countVectorsByKb 吞掉异常后返回 0，不探测就无法区分
        // 「Redis 连不上」与「Redis 连得上但确实没有数据」，只能给出误导性的排查提示
        boolean redisOk = redisVectorService.ping();
        int kbCount = 0;
        long chunkCount = 0;
        long vectorCount = 0;
        try {
            List<KnowledgeBase> bases = kbRepo.findAllByOrderByCreatedAtDesc();
            kbCount = bases.size();
            for (KnowledgeBase kb : bases) {
                chunkCount += chunkRepo.countByKbId(kb.getId());
                // Redis 已判定不可用时不再逐库统计，避免每个知识库都刷一条 warn 日志
                if (redisOk) {
                    vectorCount += redisVectorService.countVectorsByKb(kb.getId());
                }
            }
        } catch (Exception e) {
            // 数据库不可用时状态接口本身不能 500：返回可读的降级说明
            log.warn("知识库状态自检失败：{}", e.toString(), e);
            return new RagStatus(embeddingReady, redisOk, kbCount, chunkCount, vectorCount, true,
                    "知识库状态检查失败（数据库不可访问），知识检索暂不可用，请检查数据库服务。");
        }
        String hint = hintFor(embeddingReady, redisOk, chunkCount, vectorCount);
        return new RagStatus(embeddingReady, redisOk, kbCount, chunkCount, vectorCount, hint != null, hint);
    }

    /**
     * 生成状态页的降级说明（包级可见，便于不启动 Spring 的单元测试直接断言）。
     *
     * <p>判定顺序即排查顺序：Redis 是否连得上 → 有没有内容 → 向量化是否配置 → 向量是否齐全。
     * 顺序不能颠倒：Redis 不通时 vectorCount 恒为 0，若先判 vectorCount 就会把
     * 「Redis 没启动」误报成「向量丢了，请重建」，把用户引向错误的修复动作。
     *
     * @return 需要展示的说明；完全就绪时返回 {@code null}
     */
    static String hintFor(boolean embeddingReady, boolean redisOk, long chunkCount, long vectorCount) {
        if (!redisOk) {
            return "Redis 未连通，知识检索暂不可用（读不到任何向量索引）。"
                    + "请先启动本地 Redis（默认 localhost:6379）后刷新状态。";
        }
        if (chunkCount == 0) {
            return null;
        }
        if (!embeddingReady) {
            return "已有 " + chunkCount + " 个分块，但向量化模型未就绪，知识检索暂不可用。"
                    + "请到「设置 → 向量化模型」配置 Base URL / Key / 模型后点「重建向量」。";
        }
        if (vectorCount == 0) {
            return "已有 " + chunkCount + " 个分块，但 Redis 中一个向量都没有（入库时向量化失败或 Redis 丢过数据）。"
                    + "请修复向量化配置后点「重建向量」。";
        }
        if (vectorCount < chunkCount) {
            return "已有 " + chunkCount + " 个分块，但 Redis 中只有 " + vectorCount + " 个向量，"
                    + "缺失 " + (chunkCount - vectorCount) + " 个，检索会漏掉这些分块。"
                    + "请检查 Redis 持久化后点「重建向量」补齐。";
        }
        return null;
    }

    /** 包级可见：便于不启动 Spring 的单元测试直接断言降级判定；口径须与 {@link #hintFor} 一致 */
    static boolean degraded(RagStatus status) {
        return !status.redisOk()
                || (status.chunkCount() > 0
                    && (!status.embeddingReady() || status.vectorCount() < status.chunkCount()));
    }

    // ====================== 检索 ======================

    /** 检索并只返回召回内容；失败/未就绪/无命中均返回空列表，调用方无需区分原因 */
    public List<RetrievedChunk> search(String query, int topK) {
        return searchExplain(query, topK).chunks();
    }

    /**
     * 检索并返回「为什么是这些结果」：把「查询为空」「向量化未就绪」「向量化失败」
     * 「索引为空」「无命中」「向量与正文不一致」区分开，不再让六种完全不同的原因
     * 都表现为一个空列表。
     */
    public SearchExplain searchExplain(String query, int topK) {
        if (query == null || query.isBlank()) {
            return new SearchExplain(SearchExplain.Reason.BLANK_QUERY, "检索内容为空，未执行知识检索。", List.of());
        }
        if (!embeddingService.isReady()) {
            return new SearchExplain(SearchExplain.Reason.EMBEDDING_NOT_READY,
                    "向量化模型未配置或不可用，知识检索暂不可用。请到「设置 → 向量化模型」完成配置后点「重建向量」。",
                    List.of());
        }
        float[] qv = embeddingService.embedOne(query.trim());
        if (qv == null) {
            return new SearchExplain(SearchExplain.Reason.EMBEDDING_FAILED,
                    "查询向量化失败" + embeddingService.failureSuffix() + "，知识检索暂不可用。", List.of());
        }

        List<Long> kbIds = new ArrayList<>(redisVectorService.getAllKbIds());
        Map<Long, Long> chunkToKb = new HashMap<>();
        List<Long> allChunkIds = new ArrayList<>();
        for (Long kbId : kbIds) {
            for (Long cid : redisVectorService.getChunkIds(kbId)) {
                if (chunkToKb.put(cid, kbId) == null) {
                    allChunkIds.add(cid);
                }
            }
        }
        if (allChunkIds.isEmpty()) {
            return new SearchExplain(SearchExplain.Reason.INDEX_EMPTY,
                    "知识库中还没有任何向量索引（尚未入库，或入库时向量化失败 / Redis 丢过数据），请先上传内容或点「重建向量」。",
                    List.of());
        }

        // 批量取向量（单次 mget）
        List<float[]> vectors = redisVectorService.getVectors(allChunkIds);
        List<Scored> ranked = new ArrayList<>();
        for (int i = 0; i < allChunkIds.size(); i++) {
            float[] cv = vectors.get(i);
            if (cv == null) continue;
            double s = cosine(qv, cv);
            if (s >= MIN_SCORE) {
                ranked.add(new Scored(allChunkIds.get(i), s));
            }
        }
        if (ranked.isEmpty()) {
            return new SearchExplain(SearchExplain.Reason.NO_HIT,
                    "已扫描 " + allChunkIds.size() + " 个分块，没有与问题足够相关的内容（相似度阈值 " + MIN_SCORE + "）。",
                    List.of());
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());
        int n = Math.max(1, Math.min(topK, TOP_K));
        List<Scored> top = ranked.stream().limit(n).toList();
        List<Long> topIds = top.stream().map(Scored::id).toList();

        // 只对 top-N 查正文
        Map<Long, String> contents = new HashMap<>();
        chunkRepo.findAllById(topIds).forEach(c -> contents.put(c.getId(), c.getContent()));

        // 知识库名称只查 top-N 涉及到的 kb（单次 findAllById）
        Set<Long> topKbIds = new HashSet<>();
        for (Long cid : topIds) {
            Long kid = chunkToKb.get(cid);
            if (kid != null) {
                topKbIds.add(kid);
            }
        }
        Map<Long, String> kbNames = new HashMap<>();
        kbRepo.findAllById(topKbIds).forEach(kb -> kbNames.put(kb.getId(), kb.getName()));

        Map<Long, Double> scoreById = top.stream()
                .collect(Collectors.toMap(Scored::id, Scored::score));
        List<RetrievedChunk> out = new ArrayList<>();
        for (Long cid : topIds) {
            String content = contents.get(cid);
            if (content == null || content.isBlank()) {
                // Redis 里有向量、MySQL 里却查不到正文：向量孤儿（分块已删，或上次写入事务中途回滚，
                // 因为 Redis 不参与 JPA 事务）。必须跳过——否则会把字面 "null" 拼进 LLM 的 RAG 上下文。
                continue;
            }
            Long kbId = chunkToKb.get(cid);
            out.add(new RetrievedChunk(kbId, kbNames.getOrDefault(kbId, "知识库"),
                    cid, content, scoreById.getOrDefault(cid, 0.0)));
        }
        if (out.isEmpty()) {
            return new SearchExplain(SearchExplain.Reason.INDEX_INCONSISTENT,
                    "检索命中了向量，但对应分块正文已不存在（数据不一致），建议点「重建向量」修复索引。", out);
        }
        return new SearchExplain(SearchExplain.Reason.OK, null, out);
    }

    /**
     * 拼装注入到 LLM 系统提示词的知识库上下文。
     * 无命中返回 null（调用方不加任何 RAG 提示）。
     */
    public String retrieveContext(String query) {
        // RAG 失败不得影响对话主链路：整体兜底
        try {
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
        } catch (Exception e) {
            // 只降级为「无 RAG 上下文」，但必须留痕，便于排查（embedding / Redis 故障等）
            log.warn("知识库检索失败，本轮对话不注入 RAG 上下文：{}", e.toString(), e);
            return null;
        }
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

    private record Scored(Long id, double score) {
    }
}
