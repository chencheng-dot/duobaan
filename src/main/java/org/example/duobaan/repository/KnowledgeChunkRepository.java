package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByKbIdOrderByChunkIndexAsc(Long kbId);

    long countByKbId(Long kbId);

    long deleteByKbId(Long kbId);
}