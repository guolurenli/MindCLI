package com.mindcli.platform.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WriteScopeRulesTest {

    @Test
    void normalizeScopesFiltersBlankAndDeduplicates() {
        assertEquals(List.of(), WriteScopeRules.normalizeScopes(null));
        assertEquals(List.of(), WriteScopeRules.normalizeScopes(List.of()));
        assertEquals(List.of("a", "b"),
                WriteScopeRules.normalizeScopes(List.of("  ", " a ", "a", " b ", "b")));
    }

    @Test
    void formatScopesJoinsWithCommaSpace() {
        assertEquals("", WriteScopeRules.formatScopes(List.of()));
        assertEquals("a, b", WriteScopeRules.formatScopes(List.of("a", "b")));
    }

    @Test
    void overlapsDetectsEqualScopes() {
        assertTrue(WriteScopeRules.overlaps(List.of("src/a/**"), List.of("src/a/**")));
    }

    @Test
    void overlapsDetectsParentChildContainment() {
        assertTrue(WriteScopeRules.overlaps(List.of("src/a/**"), List.of("src/a/b/**")));
        assertTrue(WriteScopeRules.overlaps(List.of("src/a/b/**"), List.of("src/a/**")));
    }

    @Test
    void overlapsTreatsDisjointScopesAsNonOverlapping() {
        assertFalse(WriteScopeRules.overlaps(List.of("src/a/**"), List.of("src/b/**")));
    }

    @Test
    void overlapsNormalizesBackslashAndCase() {
        assertTrue(WriteScopeRules.overlaps(List.of("src\\a\\**"), List.of("src/a/**")));
        assertTrue(WriteScopeRules.overlaps(List.of("Src/A/**"), List.of("src/a/**")));
    }

    @Test
    void overlapsIgnoresEmptyScopes() {
        assertFalse(WriteScopeRules.overlaps(List.of(), List.of("src/a/**")));
        assertFalse(WriteScopeRules.overlaps(List.of("  "), List.of("src/a/**")));
    }

    @Test
    void containsPathAcceptsPathWithinScope(@TempDir Path tempDir) {
        Path target = tempDir.resolve("src/main/java/Foo.java");
        assertTrue(WriteScopeRules.containsPath(List.of("src/main/java/**"), tempDir, target));
        assertTrue(WriteScopeRules.containsPath(List.of("src/main/java"), tempDir, target));
    }

    @Test
    void containsPathRejectsPathOutsideScope(@TempDir Path tempDir) {
        Path target = tempDir.resolve("src/main/java/Foo.java");
        assertFalse(WriteScopeRules.containsPath(List.of("src/other/**"), tempDir, target));
        assertFalse(WriteScopeRules.containsPath(List.of("src/**"), tempDir, tempDir.resolve("README.md")));
    }

    @Test
    void containsPathHandlesExactFileScope(@TempDir Path tempDir) {
        assertTrue(WriteScopeRules.containsPath(List.of("README.md"), tempDir, tempDir.resolve("README.md")));
    }

    @Test
    void containsPathReturnsFalseForEmptyScope(@TempDir Path tempDir) {
        assertFalse(WriteScopeRules.containsPath(List.of(), tempDir, tempDir.resolve("README.md")));
    }
}
