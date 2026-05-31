import java.sql.*;
public class CheckKey {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            try (Statement s = c.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT api_key, base_url, default_model FROM llm_config WHERE id=1");
                if (rs.next()) {
                    String key = rs.getString("api_key");
                    String url = rs.getString("base_url");
                    String model = rs.getString("default_model");
                    System.out.println("Key: [" + key + "] len=" + key.length());
                    System.out.println("URL: [" + url + "]");
                    System.out.println("Model: [" + model + "]");
                    // Print char codes
                    for (int i = 0; i < key.length(); i++) {
                        System.out.print((int)key.charAt(i) + " ");
                    }
                    System.out.println();
                }
            }
        }
    }
}
