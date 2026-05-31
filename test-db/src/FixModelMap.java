import java.sql.*;
public class FixModelMap {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        String pw = "123456";
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", pw)) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("ALTER TABLE llm_config ALTER COLUMN model_map TYPE text");
                System.out.println("Column model_map altered to text");
            }
        }
    }
}
