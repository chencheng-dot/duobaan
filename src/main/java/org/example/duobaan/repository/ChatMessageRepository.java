package org.example.duobaan.repository;

import java.util.List;

import org.example.duobaan.model.ChatMessage;
import org.example.duobaan.model.ChatMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByModeOrderByCreatedAtAsc(ChatMode mode);
}
