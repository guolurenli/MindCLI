package com.mindcli.runtime.run;

@FunctionalInterface
interface LegacyAgentRunner {
    String run(String input) throws Exception;
}
