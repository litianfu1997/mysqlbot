package com.example.mysqlbot.service;

import com.example.mysqlbot.model.DataSource;
import com.example.mysqlbot.repository.DataSourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConnectionPoolService}.
 * Focuses on error handling and eviction safety; actual pool creation
 * requires a live database and is covered by integration testing.
 */
@ExtendWith(MockitoExtension.class)
class ConnectionPoolServiceTest {

    @Mock
    private DataSourceRepository dataSourceRepository;

    @InjectMocks
    private ConnectionPoolService poolService;

    // ---- getConnection(Long) ----

    @Test
    void getConnection_throwsWhenDataSourceNotFound() {
        when(dataSourceRepository.findById(999L)).thenReturn(Optional.empty());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> poolService.getConnection(999L));
        assertTrue(ex.getMessage().contains("not found"));
    }

    // ---- evictPool ----

    @Test
    void evictPool_safeWhenPoolDoesNotExist() {
        assertDoesNotThrow(() -> poolService.evictPool(123L));
    }

    @Test
    void evictPool_canBeCalledMultipleTimesSafely() {
        poolService.evictPool(1L);
        poolService.evictPool(1L);
        assertDoesNotThrow(() -> poolService.evictPool(1L));
    }

    // ---- cleanup ----

    @Test
    void cleanup_safeWhenNoPoolsExist() {
        assertDoesNotThrow(() -> poolService.cleanup());
    }
}