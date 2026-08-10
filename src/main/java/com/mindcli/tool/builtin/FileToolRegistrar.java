package com.mindcli.tool.builtin;

import com.mindcli.tool.ToolRegistry;
import com.mindcli.tool.registry.ToolRegistrar;
import com.mindcli.tool.registry.ToolRegistrationContext;

public class FileToolRegistrar implements ToolRegistrar {
    @Override
    public void register(ToolRegistrationContext context) {
        ToolRegistrationContext.ToolExecutors executors = context.executors();

        context.register(new ToolRegistry.Tool(
                "read_file",
                "读取文件内容（仅限项目根目录之内）；可用 offset/limit 按行读取，避免把大文件整段塞进上下文",
                context.parameters(
                        new ToolRegistrationContext.Parameter("path", "string", "文件路径", true),
                        new ToolRegistrationContext.Parameter("offset", "integer", "起始行号，1 表示第一行；省略时读取全文", false),
                        new ToolRegistrationContext.Parameter("limit", "integer", "最多读取多少行；省略时读取全文，最大 2000 行", false)
                ),
                executors::readFileTool
        ));

        context.register(new ToolRegistry.Tool(
                "write_file",
                "写入文件内容（仅限项目根目录之内，单文件 5MB 上限）",
                context.parameters(
                        new ToolRegistrationContext.Parameter("path", "string", "文件路径", true),
                        new ToolRegistrationContext.Parameter("content", "string", "文件内容", true)
                ),
                executors::writeFileTool
        ));

        context.register(new ToolRegistry.Tool(
                "list_dir",
                "列出目录内容（仅限项目根目录之内）",
                context.parameters(new ToolRegistrationContext.Parameter("path", "string", "目录路径", true)),
                executors::listDirTool
        ));

        context.register(new ToolRegistry.Tool(
                "glob_files",
                "按文件名 glob 查找项目内文件（只读、实时、尊重常见忽略目录）；适合先定位候选文件，例如 **/*Service.java",
                context.parameters(
                        new ToolRegistrationContext.Parameter("pattern", "string", "glob 模式，例如 **/*.java、**/*Controller*、README.md", true),
                        new ToolRegistrationContext.Parameter("path", "string", "搜索起始目录，默认 .", false),
                        new ToolRegistrationContext.Parameter("max_results", "integer", "最多返回结果数，默认 50，上限 200", false)
                ),
                executors::globFilesTool
        ));

        context.register(new ToolRegistry.Tool(
                "grep_code",
                "在项目内按关键字或正则实时搜索代码（只读、优先 ripgrep、返回文件和行号）；适合精确符号/字符串定位，找到后再 read_file 读取上下文",
                context.parameters(
                        new ToolRegistrationContext.Parameter("pattern", "string", "要搜索的关键字或正则", true),
                        new ToolRegistrationContext.Parameter("path", "string", "搜索起始目录，默认 .", false),
                        new ToolRegistrationContext.Parameter("glob", "string", "可选文件 glob 过滤，例如 **/*.java", false),
                        new ToolRegistrationContext.Parameter("regex", "boolean", "是否按 Java 正则解释 pattern，默认 false 表示字面量搜索", false),
                        new ToolRegistrationContext.Parameter("case_sensitive", "boolean", "是否大小写敏感，默认 true", false),
                        new ToolRegistrationContext.Parameter("context_lines", "integer", "每条命中前后上下文行数，默认 0，上限 5", false),
                        new ToolRegistrationContext.Parameter("max_results", "integer", "最多返回命中数，默认 50，上限 200", false),
                        new ToolRegistrationContext.Parameter("head_limit", "integer", "单个文件最多返回多少条命中，默认 20，上限 50", false),
                        new ToolRegistrationContext.Parameter("max_chars", "integer", "单次工具结果字符预算，默认 24000，上限 60000", false)
                ),
                executors::grepCodeTool
        ));
    }
}
