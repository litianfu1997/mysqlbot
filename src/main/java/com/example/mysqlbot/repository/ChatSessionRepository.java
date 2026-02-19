package com.example.mysqlbot.repository;

import com.example.mysqlbot.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
    List<ChatSession> findByDataSourceIdOrderByCreatedAtDesc(Long dataSourceId);

    List<ChatSession> findAllByOrderByCreatedAtDesc();
}
