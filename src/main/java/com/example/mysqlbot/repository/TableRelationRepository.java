package com.example.mysqlbot.repository;

import com.example.mysqlbot.model.TableRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TableRelationRepository extends JpaRepository<TableRelation, Long> {

    /**
     * 查询指定数据源中处于激活状态的关系记录
     */
    List<TableRelation> findByDataSourceIdAndIsActive(Long dataSourceId, Short isActive);

    /**
     * 图扩展查询：返回与给定表集合相关联的所有关系（from 或 to 方向均匹配）
     */
    @Query("SELECT r FROM TableRelation r WHERE r.dataSourceId = :dataSourceId AND r.isActive = 1 AND (r.fromTable IN :tables OR r.toTable IN :tables)")
    List<TableRelation> findRelationsInvolvingTables(@Param("dataSourceId") Long dataSourceId,
                                                     @Param("tables") List<String> tables);

    /**
     * 按来源批量删除（重同步时调用，保留 manual 类型记录）
     */
    @Modifying
    @Query("DELETE FROM TableRelation r WHERE r.dataSourceId = :dataSourceId AND r.source IN :sources")
    void deleteByDataSourceIdAndSourceIn(@Param("dataSourceId") Long dataSourceId,
                                         @Param("sources") List<String> sources);
}
