package com.example.mysqlbot.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecurityContextTest {

    @AfterEach
    void cleanup() {
        SecurityContext.clear();
    }

    @Test
    void noContext_returnsNull() {
        assertNull(SecurityContext.getPermissionRule());
    }

    @Test
    void adminUser_returnsNull() {
        SecurityContext.set(new SecurityContext.UserContext("1", "admin", "admin", null, null, true));
        assertNull(SecurityContext.getPermissionRule());
    }

    @Test
    void userWithTenantOnly() {
        SecurityContext.set(new SecurityContext.UserContext("2", "user1", "user", null, "tenant-abc", false));
        assertEquals("tenant_id = 'tenant-abc'", SecurityContext.getPermissionRule());
    }

    @Test
    void userWithDeptOnly() {
        SecurityContext.set(new SecurityContext.UserContext("3", "user2", "user", "dept-100", null, false));
        assertEquals("dept_id = 'dept-100'", SecurityContext.getPermissionRule());
    }

    @Test
    void userWithTenantAndDept() {
        SecurityContext.set(new SecurityContext.UserContext("4", "user3", "user", "dept-200", "tenant-xyz", false));
        String rule = SecurityContext.getPermissionRule();
        assertNotNull(rule);
        assertTrue(rule.contains("tenant_id = 'tenant-xyz'"));
        assertTrue(rule.contains(" AND "));
        assertTrue(rule.contains("dept_id = 'dept-200'"));
    }

    @Test
    void userWithNoFilters() {
        SecurityContext.set(new SecurityContext.UserContext("5", "user4", "user", null, null, false));
        assertNull(SecurityContext.getPermissionRule());
    }

    @Test
    void isAuthenticated_withContext() {
        SecurityContext.set(new SecurityContext.UserContext("1", "admin", "admin", null, null, true));
        assertTrue(SecurityContext.isAuthenticated());
    }

    @Test
    void isAuthenticated_withoutContext() {
        assertFalse(SecurityContext.isAuthenticated());
    }

    @Test
    void sqlInjectionInTenantId_singleQuotesEscaped() {
        SecurityContext.set(new SecurityContext.UserContext("6", "hacker", "user", null, "t'; DROP TABLE users;--", false));
        String rule = SecurityContext.getPermissionRule();
        assertNotNull(rule);
        // Single quotes are escaped to '', so the injection is neutralized
        assertTrue(rule.contains("''"), "Single quotes should be escaped");
        assertTrue(rule.startsWith("tenant_id = '"));
    }
}