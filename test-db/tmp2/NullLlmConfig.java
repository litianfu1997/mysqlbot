import java.sql.*;
public class NullLlmConfig {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            try (Statement s = c.createStatement()) {
                // Create a session without llmConfigId
                s.execute("INSERT INTO chat_session (id, title, data_source_id, created_at, updated_at) VALUES ('test-no-llm', 'Test No LLM', 3, NOW(), NOW())");
                System.out.println("Created test session without LLM config");
            }
        }
    }
}
