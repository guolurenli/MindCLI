package com.mindcli.app.cli;

import com.mindcli.app.cli.command.WechatCliCommandHandler;
import com.mindcli.platform.render.PlainRenderer;
import com.mindcli.platform.render.Renderer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainWechatCommandHandlerRefactorTest {

    @Test
    void wechatStatusMatchesMainFacade() {
        Renderer renderer = new PlainRenderer();
        WechatCliCommandHandler.WechatRuntimeController runtime =
                new WechatCliCommandHandler.WechatRuntimeController(renderer);

        String viaHandler = WechatCliCommandHandler.handleWechatCommand("status", null, renderer, printStream(), runtime);
        String viaMain = Main.handleWechatCommand("status", null, renderer, printStream(), runtime);

        assertEquals(viaMain, viaHandler);
        assertEquals("微信通道未运行。输入 /wechat 启动。", viaHandler);
    }

    @Test
    void wechatStopAliasesKeepOutput() {
        Renderer renderer = new PlainRenderer();

        String stop = WechatCliCommandHandler.handleWechatCommand("stop", null, renderer, printStream(),
                new WechatCliCommandHandler.WechatRuntimeController(renderer));
        String off = WechatCliCommandHandler.handleWechatCommand("off", null, renderer, printStream(),
                new WechatCliCommandHandler.WechatRuntimeController(renderer));

        assertEquals("微信通道已停止。", stop);
        assertEquals(stop, off);
    }

    @Test
    void wechatUnknownCommandKeepsUsage() {
        Renderer renderer = new PlainRenderer();

        String output = WechatCliCommandHandler.handleWechatCommand("wat", null, renderer, printStream(),
                new WechatCliCommandHandler.WechatRuntimeController(renderer));

        assertTrue(output.contains("未知 /wechat 子命令: wat"), output);
        assertTrue(output.contains("/wechat setup"), output);
        assertTrue(output.contains("/wechat stop"), output);
    }

    private static PrintStream printStream() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }
}
