package org.example.duobaan.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.example.duobaan.model.dto.ChunkDTO;
import org.example.duobaan.model.dto.KbAddResult;
import org.example.duobaan.model.dto.KbDTO;
import org.example.duobaan.model.dto.RagStatus;
import org.example.duobaan.model.dto.RetrievedChunk;
import org.example.duobaan.model.dto.SearchExplain;
import org.example.duobaan.service.KnowledgeService;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识库接口：RAG 的知识库管理 + 检索调试。
 *
 * <p>控制器只负责「参数校验 + 委派 + 返回码」，业务判断（是否降级、是否可检索）全部在
 * {@link KnowledgeService}，避免同一判据在两处各写一份而漂移。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    /** 检索词长度上限：防止超长字符串进入向量化接口（费用与超时都由外部模型决定） */
    private static final int MAX_QUERY_LEN = 2000;

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    record KbCreate(String name, String description,
                    Integer chunkSize, Integer chunkOverlap, String splitStrategy) {}
    record DocIn(String text) {}
    record SearchIn(String query) {}

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
        return knowledgeService.addDocument(id, requireDocumentText(req.text()));
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

    /**
     * 对已有分块重新向量化（配置 embedding 后补做向量）。
     * 返回实际写入的向量数；0 写入或部分失败会返回 4xx/5xx，不再「假成功」。
     */
    @PostMapping("/bases/{id}/revectorize")
    public int revectorize(@PathVariable Long id) {
        return knowledgeService.revectorize(id);
    }

    // ====================== 检索调试 ======================

    /** 兼容旧前端：只返回召回内容 */
    @PostMapping("/search")
    public List<RetrievedChunk> search(@RequestBody SearchIn req) {
        String query = requireQuery(req.query());
        return knowledgeService.search(query, 4);
    }

    /** 新增：检索 + 空结果原因（区分「没命中」与「链路坏了」） */
    @PostMapping("/search/explain")
    public SearchExplain searchExplain(@RequestBody SearchIn req) {
        String query = requireQuery(req.query());
        return knowledgeService.searchExplain(query, 4);
    }

    // ====================== RAG 状态 ======================

    /** RAG 运行状态：配置是否就绪 + Redis 是否连通 + 分块/向量数是否对得上 */
    @GetMapping("/status")
    public RagStatus status() {
        return knowledgeService.status();
    }

    // ====================== 私有工具 ======================

    /**
     * 检索词校验：非空 + 字符数上限。
     *
     * <p>该上限只约束「查询」。查询会原样送进外部向量化接口，长度直接决定费用与超时，
     * 因此必须在入口拦；但它不能套用到文档正文上。
     */
    static String requireQuery(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入检索内容");
        }
        if (value.length() > MAX_QUERY_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "检索内容过长（上限 " + MAX_QUERY_LEN + " 字符）");
        }
        return value;
    }

    /**
     * 文档正文校验：非空 + UTF-8 体积上限，与 {@link KnowledgeService#MAX_DOC_BYTES} 共用同一常量。
     *
     * <p>这里刻意不复用检索词的字符数上限：正常文档动辄数万字符，
     * 套用 2000 字符会把合法长文直接 400 拒掉（接口层回归，Service 层的 10MB 校验根本走不到）。
     */
    static String requireDocumentText(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入文档内容");
        }
        long bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > KnowledgeService.MAX_DOC_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文档内容过大（上限 10MB），当前约 " + (bytes / 1024) + " KB");
        }
        return value;
    }
}
