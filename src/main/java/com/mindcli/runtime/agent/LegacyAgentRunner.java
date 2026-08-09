package com.mindcli.runtime.agent;

@FunctionalInterface
interface LegacyAgentRunner {
    String run(String input) throws Exception;
}
