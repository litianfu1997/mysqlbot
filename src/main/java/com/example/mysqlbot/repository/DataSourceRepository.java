package com.example.mysqlbot.repository;

import com.example.mysqlbot.model.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DataSourceRepository extends JpaRepository<DataSource, Long> {
    List<DataSource> findByStatus(Integer status);

    Optional<DataSource> findByName(String name);
}
