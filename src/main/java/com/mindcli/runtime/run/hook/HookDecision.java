package com.mindcli.runtime.run.hook;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import java.util.Map;

public record HookDecision(
        HookDecisionType type,
        String reason,
        String effectiveArgumentsJson,
        Map<String, String> metadata
) {
    public HookDecision {
        type = type == null ? HookDecisionType.ALLOW : type;
        reason = reason == null ? "" : reason;
        effectiveArgumentsJson = effectiveArgumentsJson == null ? "" : effectiveArgumentsJson;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static HookDecision allow() {
        return new HookDecision(HookDecisionType.ALLOW, "", "", Map.of());
    }

    public static HookDecision denyByPolicy(String reason) {
        return new HookDecision(HookDecisionType.DENY_BY_POLICY, reason, "", Map.of());
    }

    public static HookDecision denyByUser(String reason) {
        return new HookDecision(HookDecisionType.DENY_BY_USER, reason, "", Map.of());
    }

    public static HookDecision modifyArguments(String effectiveArgumentsJson) {
        return new HookDecision(HookDecisionType.MODIFY_ARGUMENTS, "", effectiveArgumentsJson, Map.of());
    }
}
