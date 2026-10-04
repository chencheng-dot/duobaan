package org.example.duobaan.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
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

    /** 写入一个分块的向量，并把 chunkId 登记到知识库的 chunk 集合 */
    public void storeVector(Long kbId, Long chunkId, float[] vector) {
        if (kbId == null || chunkId == null || vector == null) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(vector);
            jedis.set(vecKey(chunkId), json);
            jedis.sadd(chunksKey(kbId), String.valueOf(chunkId));
        } catch (Exception e) {
            // Redis 写入失败不阻断入库流程（分块文本已在 MySQL，向量缺失仅影响检索）
        }
    }

    /** 读取一个分块的向量 */
    public float[] getVector(Long chunkId) {
        if (chunkId == null) {
            return null;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(vecKey(chunkId));
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            return null;
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
                } catch (NumberFormatException ignore) {
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 删除某个分块的向量（同时从知识库集合移除） */
    public void deleteVector(Long kbId, Long chunkId) {
        if (chunkId == null) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(vecKey(chunkId));
            if (kbId != null) {
                jedis.srem(chunksKey(kbId), String.valueOf(chunkId));
            }
        } catch (Exception ignore) {
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
                String[] keys = members.stream().map(m -> vecKey(Long.parseLong(m))).toArray(String[]::new);
                jedis.del(keys);
            }
            jedis.del(chunksKey(kbId));
        } catch (Exception ignore) {
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

    /** 取当前 Redis 中所有知识库的 chunkId 集合（用于跨库检索时枚举） */
    public Set<Long> getAllKbIds() {
        Set<Long> ids = new HashSet<>();
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys("kb:chunks:*");
            if (keys != null) {
                for (String k : keys) {
                    try {
                        ids.add(Long.parseLong(k.substring("kb:chunks:".length())));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return ids;
    }
}
