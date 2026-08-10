package com.mindcli.capability.tool.builtin;

import com.mindcli.capability.tool.ToolRegistry;
import com.mindcli.capability.tool.registry.ToolRegistrar;
import com.mindcli.capability.tool.registry.ToolRegistrationContext;

public class BrowserToolRegistrar implements ToolRegistrar {
    @Override
    public void register(ToolRegistrationContext context) {
        ToolRegistrationContext.ToolExecutors executors = context.executors();
        context.register(new ToolRegistry.Tool(
                "browser_connect",
                "当浏览器页面返回登录页、权限不足或明确需要登录态时，自动连接已允许远程调试的本机 Chrome 并复用其登录态；公开页面不要提前调用。",
                context.parameters(),
                executors::browserConnectTool
        ));
        context.register(new ToolRegistry.Tool(
                "browser_disconnect",
                "完成登录态页面访问后，可切回 isolated 浏览器模式。",
                context.parameters(),
                executors::browserDisconnectTool
        ));
        context.register(new ToolRegistry.Tool(
                "browser_status",
                "查看当前浏览器 MCP 模式、autoConnect 引导和旧式 CDP 端口探活状态。",
                context.parameters(),
                executors::browserStatusTool
        ));
    }
}
