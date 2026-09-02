package com.mindcli.runtime.run.hook;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.dispatch.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

public enum HookType {
    PRE_TOOL_USE,
    POST_TOOL_USE,
    TOOL_ERROR,
    RUN_STOP
}
