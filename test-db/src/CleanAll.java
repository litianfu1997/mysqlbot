import java.sql.*;
public class CleanAll {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM llm_config");
                s.execute("DELETE FROM system_config WHERE config_key LIKE 'llm.%'");
                s.execute("DELETE FROM data_source");
                s.execute("DELETE FROM chat_message");
                s.execute("DELETE FROM chat_session");
                System.out.println("All cleaned up");
            }
        }
    }
}
