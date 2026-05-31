import java.nio.file.*;
public class FixImports {
    public static void main(String[] args) throws Exception {
        Path p = Paths.get("D:/IdeaProjects/mysqlbot/src/main/java/com/example/mysqlbot/service/SqlGenerateService.java");
        String content = Files.readString(p);
        // Add missing imports
        if (!content.contains("import java.sql.Connection;")) {
            content = content.replace("import java.nio.charset.StandardCharsets;",
                "import java.sql.Connection;\nimport java.sql.DatabaseMetaData;\nimport java.sql.DriverManager;\nimport java.sql.ResultSet;\nimport java.nio.charset.StandardCharsets;");
        }
        if (!content.contains("import com.example.mysqlbot.repository.SqlExampleRepository;")) {
            content = content.replace("import com.example.mysqlbot.repository.DataSourceRepository;",
                "import com.example.mysqlbot.repository.DataSourceRepository;\nimport com.example.mysqlbot.repository.SqlExampleRepository;");
        }
        // Add sqlExampleRepository field
        if (!content.contains("sqlExampleRepository")) {
            content = content.replace("private final TermGlossaryRepository termGlossaryRepository;",
                "private final TermGlossaryRepository termGlossaryRepository;\n    private final SqlExampleRepository sqlExampleRepository;");
        }
        Files.writeString(p, content);
        System.out.println("Fixed imports");
    }
}
