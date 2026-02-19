package com.example.mysqlbot.repository;

import com.example.mysqlbot.model.TermGlossary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TermGlossaryRepository extends JpaRepository<TermGlossary, Long> {

    // 查询指定数据源的术语，或者全局术语 (dataSourceId IS NULL)
    List<TermGlossary> findByDataSourceIdOrDataSourceIdIsNull(Long dataSourceId);

    // 仅查询指定数据源的术语
    List<TermGlossary> findByDataSourceId(Long dataSourceId);
}
