import java.sql.*;
public class FixDb {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        String pw = System.getenv().getOrDefault("PG_PASSWORD", "123456");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", pw)) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM llm_config");
                System.out.println("Cleared llm_config table");
                // Also check and fix the model_map column type
                ResultSet rs = s.executeQuery("SELECT data_type FROM information_schema.columns WHERE table_name='llm_config' AND column_name='model_map'");
                if (rs.next()) System.out.println("model_map type: " + rs.getString(1));
            }
        }
    }
}
