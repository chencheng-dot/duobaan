package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    List<KnowledgeBase> findAllByOrderByCreatedAtDesc();
}