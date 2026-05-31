import java.sql.*;
public class CheckCol {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            try (Statement s = c.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT column_name, data_type FROM information_schema.columns WHERE table_name='llm_config' ORDER BY ordinal_position");
                while (rs.next()) System.out.println(rs.getString(1) + ": " + rs.getString(2));
            }
        }
    }
}
