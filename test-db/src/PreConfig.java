import java.sql.*;
public class PreConfig {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/mysqlbot", "postgres", "123456")) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO system_config (config_key, config_value, description) VALUES ('llm.base_url', 'https://api.deepseek.com', 'LLM Base URL') ON CONFLICT (config_key) DO UPDATE SET config_value = 'https://api.deepseek.com'");
                s.execute("INSERT INTO system_config (config_key, config_value, description) VALUES ('llm.api_key', 'sk-0bcfdd777a3745e4b6803ff5bcd65613', 'LLM API Key') ON CONFLICT (config_key) DO UPDATE SET config_value = 'sk-0bcfdd777a3745e4b6803ff5bcd65613'");
                s.execute("INSERT INTO system_config (config_key, config_value, description) VALUES ('llm.default_model', 'DeepSeek-V4-Flash', 'LLM Default Model') ON CONFLICT (config_key) DO UPDATE SET config_value = 'DeepSeek-V4-Flash'");
                s.execute("INSERT INTO system_config (config_key, config_value, description) VALUES ('llm.model_map', '{\"DeepSeek-V4-Flash\":\"deepseek-v4-flash\",\"DeepSeek-V4-Pro\":\"deepseek-v4-pro\"}', 'LLM Model Map') ON CONFLICT (config_key) DO UPDATE SET config_value = '{\"DeepSeek-V4-Flash\":\"deepseek-v4-flash\",\"DeepSeek-V4-Pro\":\"deepseek-v4-pro\"}'");
                s.execute("INSERT INTO system_config (config_key, config_value, description) VALUES ('llm.temperature', '0.6', 'LLM Temperature') ON CONFLICT (config_key) DO UPDATE SET config_value = '0.6'");
                System.out.println("Pre-configured LLM settings in system_config");
            }
        }
    }
}
