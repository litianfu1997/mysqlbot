import java.nio.file.*;
public class FixJdbc {
    public static void main(String[] args) throws Exception {
        Path p = Paths.get("D:/IdeaProjects/mysqlbot/src/main/java/com/example/mysqlbot/service/SqlGenerateService.java");
        String content = Files.readString(p);
        // Fix: replace getDialect().getJdbcPrefix() with buildJdbcUrl
        content = content.replace(
            "String url = \"jdbc:\" + ds.getDialect().getJdbcPrefix() + \"://\" + ds.getHost() + \":\" + ds.getPort() + \"/\" + ds.getDbName();",
            "String url = ds.getDialect().buildJdbcUrl(ds.getHost(), ds.getPort(), ds.getDbName());"
        );
        Files.writeString(p, content);
        System.out.println("Fixed JDBC URL construction");
    }
}
