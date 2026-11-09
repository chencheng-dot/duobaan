package org.example.duobaan.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import tools.jackson.databind.ObjectMapper;

/**
 * 向量存储服务：把知识库分块的 embedding 向量存到 Redis。
 *
 * Key 设计：
 *   kb:chunks:{kbId}         → Set<String>  该知识库下所有 chunkId（用于检索时枚举）
 *   kb:vec:{chunkId}         → String       该分块向量的 JSON float 数组
 *
 * 检索时从 kb:chunks:{kbId} 取所有 chunkId，批量 GET 向量后在内存算余弦相似度。
 * 不依赖 RediSearch 向量索引，避免模块/参数兼容问题，契合「本地普通 Redis」环境。
 */
@Service
public class RedisVectorService {

    private static final Logger log = LoggerFactory.getLogger(RedisVectorService.class);

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public RedisVectorService(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    private static String chunksKey(Long kbId) {
        return "kb:chunks:" + kbId;
    }

    private static String vecKey(Long chunkId) {
        return "kb:vec:" + chunkId;
    }

    /**
     * 写入一个分块的向量，并把 chunkId 登记到知识库的 chunk 集合。
     *
     * @return 是否真正写入成功。失败时不抛异常（不阻断入库主流程），但必须让调用方
     *         能统计到「实际写入数」——旧实现完全静默，导致 0 个向量写入被当成成功。
     */
    public boolean storeVector(Long kbId, Long chunkId, float[] vector) {
        if (kbId == null || chunkId == null || vector == null) {
            return false;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(vector);
            jedis.set(vecKey(chunkId), json);
            jedis.sadd(chunksKey(kbId), String.valueOf(chunkId));
            return true;
        } catch (Exception e) {
            // Redis 写入失败不阻断入库流程（分块文本已在 MySQL），但必须留痕便于排查
            log.warn("写入向量失败（kbId={}, chunkId={}）：{}", kbId, chunkId, e.toString(), e);
            return false;
        }
    }

    /**
     * 统计某知识库「已登记」的分块向量数（SCARD）。
     *
     * <p>以 chunk 集合成员为准，不再逐个校验向量键是否存在——用于状态页快速判断
     * 「分块数与向量数是否匹配」；残留孤儿成员会被计入，需要「重建向量」清理。
     */
    public long countVectorsByKb(Long kbId) {
        if (kbId == null) {
            return 0;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.scard(chunksKey(kbId));
        } catch (Exception e) {
            log.warn("统计知识库 {} 的已登记向量数失败：{}", kbId, e.toString(), e);
            return 0;
        }
    }

    /** 取某知识库下所有分块的 chunkId */
    public List<Long> getChunkIds(Long kbId) {
        if (kbId == null) {
            return List.of();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> members = jedis.smembers(chunksKey(kbId));
            List<Long> ids = new ArrayList<>();
            for (String m : members) {
                try {
                    ids.add(Long.parseLong(m));
                } catch (NumberFormatException e) {
                    // 脏 member（非数字 chunkId）只跳过自己，但必须留痕，避免"静默少数据"
                    log.warn("知识库 {} 的 chunks 集合存在非法成员「{}」，已跳过", kbId, m);
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 清空某个知识库的全部向量 */
    public void deleteVectorsByKb(Long kbId) {
        if (kbId == null) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> members = jedis.smembers(chunksKey(kbId));
            if (members != null && !members.isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (String m : members) {
                    try {
                        keys.add(vecKey(Long.parseLong(m)));
                    } catch (NumberFormatException e) {
                        // 脏 member 逐条跳过并留痕：不能让它中断整库删除，也不能静默
                        log.warn("知识库 {} 的 chunks 集合存在非法成员「{}」，已跳过该向量删除", kbId, m, e);
                    }
                }
                if (!keys.isEmpty()) {
                    jedis.del(keys.toArray(new String[0]));
                }
            }
            jedis.del(chunksKey(kbId));
        } catch (Exception e) {
            // 清理失败不能吞掉：留痕便于发现 Redis 异常导致的残留向量
            log.warn("清理知识库 {} 的 Redis 向量失败：{}", kbId, e.toString(), e);
        }
    }

    /** 批量取多个分块的向量，返回与 ids 等长（缺失位置为 null） */
    public List<float[]> getVectors(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String[] keys = ids.stream().map(id -> vecKey(id)).toArray(String[]::new);
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> jsons = jedis.mget(keys);
            List<float[]> out = new ArrayList<>(ids.size());
            for (String json : jsons) {
                if (json == null || json.isBlank()) {
                    out.add(null);
                    continue;
                }
                try {
                    out.add(objectMapper.readValue(json, float[].class));
                } catch (Exception e) {
                    out.add(null);
                }
            }
            return out;
        } catch (Exception e) {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) out.add(null);
            return out;
        }
    }

    /** 连通性探测（用于前端展示 Redis 是否可用） */
    public boolean ping() {
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    /** 取当前 Redis 中所有知识库的 kbId 集合（用于跨库检索时枚举）；用 SCAN 替代 KEYS，避免阻塞 */
    public Set<Long> getAllKbIds() {
        Set<Long> ids = new HashSet<>();
        try (Jedis jedis = jedisPool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match("kb:chunks:*").count(200);
            do {
                ScanResult<String> rs = jedis.scan(cursor, params);
                for (String k : rs.getResult()) {
                    try {
                        ids.add(Long.parseLong(k.substring("kb:chunks:".length())));
                    } catch (NumberFormatException e) {
                        log.warn("SCAN 到非法知识库 key「{}」，已跳过", k);
                    }
                }
                cursor = rs.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (Exception e) {
            log.warn("SCAN 枚举知识库 kbId 失败：{}", e.getMessage());
        }
        return ids;
    }
}
