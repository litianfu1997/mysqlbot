import java.sql.*;
public class CheckModelMap {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            try (Statement s = c.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT model_map, default_model FROM llm_config WHERE id=1");
                if (rs.next()) {
                    String map = rs.getString("model_map");
                    String model = rs.getString("default_model");
                    System.out.println("model_map: " + map);
                    System.out.println("default_model: " + model);
                }
            }
        }
    }
}
