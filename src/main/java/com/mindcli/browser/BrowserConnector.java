package com.mindcli.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
