import java.nio.file.*;
public class FixFile {
    public static void main(String[] args) throws Exception {
        Path p = Paths.get("D:/IdeaProjects/mysqlbot/src/main/java/com/example/mysqlbot/service/SqlGenerateService.java");
        String content = Files.readString(p);
        // Fix literal backslash-n sequences
        content = content.replace("}\\\\n\\\\n    private static String loadResource", "}\n\n    private static String loadResource");
        Files.writeString(p, content);
        System.out.println("Fixed");
    }
}
