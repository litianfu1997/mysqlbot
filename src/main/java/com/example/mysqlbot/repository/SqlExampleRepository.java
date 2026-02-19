package com.example.mysqlbot.repository;

import com.example.mysqlbot.model.SqlExample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SqlExampleRepository extends JpaRepository<SqlExample, Long> {

    List<SqlExample> findByDataSourceId(Long dataSourceId);

    // 可以添加更多方法，例如根据问题相似度检索（虽然这里主要是 RAG 负责，但简单匹配也可以）
}
