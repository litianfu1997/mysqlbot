package com.example.mysqlbot.repository;

import com.example.mysqlbot.model.LlmConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmConfigRepository extends JpaRepository<LlmConfig, Long> {

    List<LlmConfig> findByIsEnabledTrue();

    Optional<LlmConfig> findByIsDefaultTrue();

    Optional<LlmConfig> findByName(String name);

    boolean existsByName(String name);
}
