package com.mindcli.capability.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
