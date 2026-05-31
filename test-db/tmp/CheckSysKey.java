import java.sql.*;
public class CheckSysKey {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            try (Statement s = c.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT config_key, config_value FROM system_config WHERE config_key LIKE 'llm.%%' ORDER BY config_key");
                while (rs.next()) {
                    System.out.println(rs.getString("config_key") + " = [" + rs.getString("config_value") + "]");
                }
            }
        }
    }
}
