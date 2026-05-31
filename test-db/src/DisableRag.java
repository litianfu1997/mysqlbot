import java.sql.*;
public class DisableRag {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        String pw = System.getenv().getOrDefault("PG_PASSWORD", "123456");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", pw)) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO system_config (config_key, config_value, description) VALUES ('rag.enabled', 'false', 'Disable RAG for testing') ON CONFLICT (config_key) DO UPDATE SET config_value = 'false'");
                System.out.println("RAG disabled in system_config");
            }
        }
    }
}
