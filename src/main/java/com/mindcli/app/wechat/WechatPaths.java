package com.mindcli.app.wechat;

import com.mindcli.platform.config.ConfigValueResolver;

import java.nio.file.Path;

public final class WechatPaths {
    private WechatPaths() {
    }

    public static Path root() {
        String configured = ConfigValueResolver.current().resolve(
                "mindcli.wechat.dir", "MINDCLI_WECHAT_DIR",
                Path.of(System.getProperty("user.home"), ".mindcli", "wechat").toString());
        return Path.of(configured);
    }

    public static Path accountsDir() {
        return root().resolve("accounts");
    }

    public static Path sessionsDir() {
        return root().resolve("sessions");
    }

    public static Path mediaDir() {
        return root().resolve("media");
    }

    public static Path logsDir() {
        return root().resolve("logs");
    }

    public static Path pidFile() {
        return root().resolve("mindcli-wechat.pid");
    }
}
