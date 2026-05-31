import java.nio.file.*;
public class FixFile2 {
    public static void main(String[] args) throws Exception {
        Path p = Paths.get("D:/IdeaProjects/mysqlbot/src/main/java/com/example/mysqlbot/service/SqlGenerateService.java");
        byte[] bytes = Files.readAllBytes(p);
        String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        // Replace literal \n (backslash + n) that appear as line separator
        content = content.replace("}\\n\\n    private static", "}\n\n    private static");
        Files.write(p, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("Fixed! Length=" + content.length());
    }
}
