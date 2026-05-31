import java.sql.*;
public class FixAgain {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM llm_config");
                s.execute("ALTER TABLE llm_config ALTER COLUMN model_map TYPE text");
                System.out.println("Fixed model_map to text and cleared llm_config");
            }
        }
    }
}
