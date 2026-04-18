package com.mindcli.wechat;

import java.io.IOException;

@FunctionalInterface
public interface WechatMessageSender {
    void send(String text) throws IOException;
}
