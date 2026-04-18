package com.mindcli.runtime.task;

@FunctionalInterface
public interface TaskRunner {
    String run(String prompt) throws Exception;
}
