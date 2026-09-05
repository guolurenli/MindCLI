package com.mindcli.capability.tool.builtin;

import com.mindcli.platform.security.PathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Executes the built-in project scaffolding tool. */
public final class ProjectToolExecutor {
    private final PathGuard pathGuard;

    public ProjectToolExecutor(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    public String create(Map<String, String> args) {
        String name = args.get("name");
        String type = args.get("type");
        Path projectRoot = pathGuard.resolveSafe(name);
        try {
            Files.createDirectories(projectRoot);

            switch (type.toLowerCase()) {
                case "java" -> {
                    Files.createDirectories(projectRoot.resolve("src/main/java"));
                    Files.createDirectories(projectRoot.resolve("src/main/resources"));
                    Files.writeString(projectRoot.resolve("pom.xml"),
                            String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                    "<project>\n" +
                                    "    <modelVersion>4.0.0</modelVersion>\n" +
                                    "    <groupId>com.example</groupId>\n" +
                                    "    <artifactId>%s</artifactId>\n" +
                                    "    <version>1.0</version>\n" +
                                    "</project>", name));
                }
                case "python" -> {
                    Files.createDirectories(projectRoot.resolve(name));
                    Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                    Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                }
                case "node" -> Files.writeString(projectRoot.resolve("package.json"),
                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
            }
            return "项目已创建: " + name + " (类型: " + type + ")";
        } catch (Exception e) {
            return "创建项目失败: " + e.getMessage();
        }
    }
}
