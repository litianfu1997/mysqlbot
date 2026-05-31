import java.sql.*;
public class FixDb2 {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        String pw = System.getenv().getOrDefault("PG_PASSWORD", "123456");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", pw)) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                // Delete system config entries for LLM (they had the wrong modelMap format)
                s.execute("DELETE FROM system_config WHERE config_key LIKE 'llm.%'");
                System.out.println("Cleared llm system_config entries");
                // Also clean data_source
                s.execute("DELETE FROM data_source");
                System.out.println("Cleared data_source entries");
            }
        }
    }
}
