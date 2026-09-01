package com.mindcli.platform.render.inline;

import com.mindcli.platform.config.ConfigValueResolver;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Startup mascot renderer backed by native chafa image rendering.
 *
 * <p>Chafa writes directly to the process terminal instead of being captured as an ANSI
 * string. This preserves the same terminal probing and color behavior as a standalone
 * chafa invocation.
 */
public final class TerminalMascotRenderer {

    private static final String IMAGE_RESOURCE_DIR = "ui";
    private static final String LEGACY_IMAGE_RESOURCE = "/ui/mindcli-neko-helper.png";
    private static final String IMAGE_RESOURCE_SUFFIX = ".png";
    private static final int CHAFA_COLUMNS = 10;
    private static final int CHAFA_ROWS = 10;
    private static final long CHAFA_TIMEOUT_MILLIS = 2_000L;
    private static final String CHAFA_BIN_PROPERTY = "mindcli.chafa.bin";
    private static final String CHAFA_BIN_ENV = "MINDCLI_CHAFA_BIN";

    private TerminalMascotRenderer() {
    }

    /**
     * Render the startup mascot directly to the real process terminal.
     *
     * @return {@code true} when chafa rendered successfully; {@code false} means callers
     * should show the text-only startup banner.
     */
    public static boolean renderStartupMascot(Terminal terminal) {
        return renderStartupMascot(terminal, new ProcessChafaRunner());
    }

    static boolean renderStartupMascot(Terminal terminal, ChafaRunner runner) {
        if (!mascotEnabled() || runner == null) {
            return false;
        }
        Optional<byte[]> image = selectStartupImageResource(startupImageResources(),
                ThreadLocalRandom.current()::nextInt).flatMap(TerminalMascotRenderer::readResourceBytes);
        return image.filter(bytes -> runner.render(bytes, CHAFA_COLUMNS, CHAFA_ROWS)).isPresent();
    }

    static List<String> startupImageResources() {
        return startupImageResources(TerminalMascotRenderer.class.getClassLoader());
    }

    static List<String> startupImageResources(ClassLoader loader) {
        ClassLoader effectiveLoader = loader == null
                ? TerminalMascotRenderer.class.getClassLoader()
                : loader;
        Set<String> resources = new LinkedHashSet<>();
        try {
            Enumeration<URL> urls = effectiveLoader.getResources(IMAGE_RESOURCE_DIR);
            while (urls.hasMoreElements()) {
                collectImageResources(urls.nextElement(), resources);
            }
        } catch (IOException ignored) {
            // Empty list falls through to the legacy single-resource probe below.
        }
        if (resources.isEmpty() && resourceExists(effectiveLoader, LEGACY_IMAGE_RESOURCE)) {
            resources.add(LEGACY_IMAGE_RESOURCE);
        }
        List<String> sorted = new ArrayList<>(resources);
        sorted.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo));
        return List.copyOf(sorted);
    }

    static Optional<String> selectStartupImageResource(List<String> resources, IntUnaryOperator indexPicker) {
        if (resources == null || resources.isEmpty()) {
            return Optional.empty();
        }
        List<String> candidates = resources.stream()
                .filter(TerminalMascotRenderer::isPngResource)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        IntUnaryOperator picker = indexPicker == null ? ThreadLocalRandom.current()::nextInt : indexPicker;
        int index = Math.floorMod(picker.applyAsInt(candidates.size()), candidates.size());
        return Optional.of(candidates.get(index));
    }

    static ProcessBuilder chafaProcessBuilder(int columns, int rows, Path image) {
        return new ProcessBuilder(command(columns, rows, image))
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    private static Optional<byte[]> readResourceBytes(String resource) {
        try (InputStream in = TerminalMascotRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            byte[] bytes = in.readAllBytes();
            return bytes.length == 0 ? Optional.empty() : Optional.of(bytes);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void collectImageResources(URL url, Set<String> resources) {
        if (url == null || resources == null) {
            return;
        }
        String protocol = url.getProtocol();
        if ("file".equalsIgnoreCase(protocol)) {
            collectFileImageResources(url, resources);
            return;
        }
        if ("jar".equalsIgnoreCase(protocol)) {
            collectJarImageResources(url, resources);
        }
    }

    private static void collectFileImageResources(URL url, Set<String> resources) {
        try {
            Path directory = Path.of(url.toURI());
            if (!Files.isDirectory(directory)) {
                return;
            }
            try (Stream<Path> children = Files.list(directory)) {
                children.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(TerminalMascotRenderer::isPngFileName)
                        .map(name -> "/" + IMAGE_RESOURCE_DIR + "/" + name)
                        .forEach(resources::add);
            }
        } catch (IOException | IllegalArgumentException | URISyntaxException ignored) {
            // Missing or unreadable resource directories simply disable the mascot fallback.
        }
    }

    private static void collectJarImageResources(URL url, Set<String> resources) {
        try {
            URLConnection connection = url.openConnection();
            if (!(connection instanceof JarURLConnection jarConnection)) {
                return;
            }
            JarFile jar = jarConnection.getJarFile();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                collectJarEntry(entries.nextElement(), resources);
            }
        } catch (IOException ignored) {
            // Missing or unreadable resource directories simply disable the mascot fallback.
        }
    }

    private static void collectJarEntry(JarEntry entry, Set<String> resources) {
        if (entry == null || entry.isDirectory()) {
            return;
        }
        String name = entry.getName();
        String prefix = IMAGE_RESOURCE_DIR + "/";
        if (!name.startsWith(prefix)) {
            return;
        }
        String fileName = name.substring(prefix.length());
        if (fileName.contains("/") || !isPngFileName(fileName)) {
            return;
        }
        resources.add("/" + name);
    }

    private static boolean resourceExists(ClassLoader loader, String resource) {
        String normalized = resource.startsWith("/") ? resource.substring(1) : resource;
        if (loader != null && loader.getResource(normalized) != null) {
            return true;
        }
        return TerminalMascotRenderer.class.getResource(resource) != null;
    }

    private static boolean isPngResource(String resource) {
        if (resource == null || resource.isBlank()) {
            return false;
        }
        int slash = resource.lastIndexOf('/');
        String fileName = slash >= 0 ? resource.substring(slash + 1) : resource;
        return isPngFileName(fileName);
    }

    private static boolean isPngFileName(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(IMAGE_RESOURCE_SUFFIX);
    }

    private static boolean mascotEnabled() {
        return ConfigValueResolver.current().resolveBoolean(
                "mindcli.ui.mascot", "MINDCLI_UI_MASCOT", true);
    }

    @FunctionalInterface
    interface ChafaRunner {
        boolean render(byte[] imageBytes, int columns, int rows);
    }

    private static final class ProcessChafaRunner implements ChafaRunner {
        @Override
        public boolean render(byte[] imageBytes, int columns, int rows) {
            if (imageBytes == null || imageBytes.length == 0) {
                return false;
            }
            Path image = null;
            try {
                image = Files.createTempFile("mindcli-neko-helper-", ".png");
                Files.write(image, imageBytes);
                Process process = TerminalMascotRenderer.chafaProcessBuilder(columns, rows, image).start();
                boolean finished = process.waitFor(CHAFA_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                if (image != null) {
                    try {
                        Files.deleteIfExists(image);
                    } catch (IOException ignored) {
                        // Best effort cleanup for a startup-only temp file.
                    }
                }
            }
        }

    }

    private static List<String> command(int columns, int rows, Path image) {
        String binary = ConfigValueResolver.current().resolve(CHAFA_BIN_PROPERTY, CHAFA_BIN_ENV, "chafa");
        return List.of(binary, "-s", columns + "x" + rows,
                "--dither", "ordered", image.toString());
    }
}
