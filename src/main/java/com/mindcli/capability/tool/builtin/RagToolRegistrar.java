package com.mindcli.capability.tool.builtin;

import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.registry.ToolRegistrar;
import com.mindcli.capability.tool.registry.ToolRegistrationContext;

public class RagToolRegistrar implements ToolRegistrar {
    @Override
    public void register(ToolRegistrationContext context) {
        ToolRegistrationContext.ToolExecutors executors = context.executors();
        context.register(new ToolRegistry.Tool(
                "search_code",
                "RAG 语义辅助检索代码库，根据自然语言描述查找相关代码块；精确符号/字符串定位请优先用 grep_code/glob_files/read_file；默认 top_k=5，可显式指定（上限 30）",
                context.parameters(
                        new ToolRegistrationContext.Parameter("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
                        new ToolRegistrationContext.Parameter("top_k", "integer", "返回结果数量（默认 5，上限 30）", false)
                ),
                executors::searchCodeTool
        ));
    }
}
