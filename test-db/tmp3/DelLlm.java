import java.sql.*;
public class DelLlm {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM llm_config");
                System.out.println("Deleted all LlmConfig entries");
            }
        }
    }
}
