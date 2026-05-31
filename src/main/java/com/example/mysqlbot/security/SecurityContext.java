package com.example.mysqlbot.security;

/**
 * Holds the current user's security context (thread-local).
 * In production, integrate with Spring Security to populate this.
 */
public class SecurityContext {

    private static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

    public static void set(UserContext ctx) {
        CURRENT.set(ctx);
    }

    public static UserContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static boolean isAuthenticated() {
        UserContext ctx = CURRENT.get();
        return ctx != null && ctx.getUserId() != null;
    }

    public static String getPermissionRule() {
        UserContext ctx = CURRENT.get();
        if (ctx == null || ctx.isAdmin()) return null;
        return ctx.getPermissionRule();
    }

    public static class UserContext {
        private String userId;
        private String username;
        private String role;
        private String deptId;
        private String tenantId;
        private boolean admin;

        public UserContext() {}

        public UserContext(String userId, String username, String role, String deptId, String tenantId, boolean admin) {
            this.userId = userId;
            this.username = username;
            this.role = role;
            this.deptId = deptId;
            this.tenantId = tenantId;
            this.admin = admin;
        }

        public String getPermissionRule() {
            if (admin) return null;
            StringBuilder rule = new StringBuilder();
            if (tenantId != null) {
                rule.append("tenant_id = '").append(tenantId.replace("'", "''")).append("'");
            }
            if (deptId != null) {
                if (!rule.isEmpty()) rule.append(" AND ");
                rule.append("dept_id = '").append(deptId.replace("'", "''")).append("'");
            }
            return rule.isEmpty() ? null : rule.toString();
        }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getDeptId() { return deptId; }
        public void setDeptId(String deptId) { this.deptId = deptId; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public boolean isAdmin() { return admin; }
        public void setAdmin(boolean admin) { this.admin = admin; }
    }
}
