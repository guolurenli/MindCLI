package com.mindcli.runtime.run.dispatch;
import com.mindcli.runtime.run.*;
import com.mindcli.runtime.run.hook.*;
import com.mindcli.runtime.run.legacy.*;
import com.mindcli.runtime.run.loop.*;
import com.mindcli.runtime.run.mode.*;
import com.mindcli.runtime.run.recovery.*;
import com.mindcli.runtime.run.session.*;
import com.mindcli.runtime.run.store.*;

import java.util.Objects;

public record ResourceKey(
        ResourceScope scope,
        String name,
        ResourceAccess access
) implements Comparable<ResourceKey> {
    public ResourceKey {
        scope = Objects.requireNonNull(scope, "scope");
        name = name == null || name.isBlank() ? "<default>" : name;
        access = Objects.requireNonNull(access, "access");
    }

    public boolean conflictsWith(ResourceKey other) {
        if (other == null) {
            return false;
        }
        return scope == other.scope
                && name.equals(other.name)
                && (access == ResourceAccess.EXCLUSIVE || other.access == ResourceAccess.EXCLUSIVE);
    }

    @Override
    public int compareTo(ResourceKey other) {
        int scopeCompare = scope.name().compareTo(other.scope.name());
        if (scopeCompare != 0) {
            return scopeCompare;
        }
        int nameCompare = name.compareTo(other.name);
        if (nameCompare != 0) {
            return nameCompare;
        }
        return access.name().compareTo(other.access.name());
    }

    @Override
    public String toString() {
        return scope.name() + ":" + name + ":" + access.name();
    }
}
