package org.sunix;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@ApplicationScoped
public class JdtlsConnectionService {

    private static final Logger LOG = Logger.getLogger(JdtlsConnectionService.class.getName());

    @ConfigProperty(name = "jdtls.install.path", defaultValue = "")
    Optional<String> jdtlsInstallPath;

    private LanguageServer languageServer;
    private Process jdtlsProcess;
    private Future<?> listenerFuture;
    private boolean connected = false;
    private String currentWorkspaceRoot;

    /** Tracks URIs of files currently open in JDTLS (via textDocument/didOpen). */
    private final Set<String> openDocuments = ConcurrentHashMap.newKeySet();

    /** Monotonically-increasing document version counter, keyed by file URI. */
    private final Map<String, Integer> documentVersions = new ConcurrentHashMap<>();

    /** Diagnostics pushed by JDTLS via publishDiagnostics, keyed by file URI. */
    private final Map<String, List<Diagnostic>> diagnosticsCache = new ConcurrentHashMap<>();

    public boolean isConnected() {
        return connected && jdtlsProcess != null && jdtlsProcess.isAlive();
    }

    public String getCurrentWorkspace() {
        return currentWorkspaceRoot;
    }

    /**
     * Start the Eclipse JDT Language Server process and connect to it via LSP4J.
     * The JDTLS home directory is resolved from the {@code jdtls.install.path}
     * configuration property, the system PATH, or common installation locations.
     */
    public CompletableFuture<String> startJdtls() {
        if (isConnected()) {
            return CompletableFuture.completedFuture(
                    "JDTLS is already running. PID: " + jdtlsProcess.pid());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String jdtlsHome = findJdtlsHome();
                if (jdtlsHome == null) {
                    return "JDTLS not found. Please install jdtls and either:\n"
                            + "  • set 'jdtls.install.path' in application.properties, or\n"
                            + "  • ensure the 'jdtls' launch script is on your PATH, or\n"
                            + "  • install to ~/.local/share/jdtls, /usr/local/jdtls, or /opt/jdtls.";
                }

                Path launcherJar = findLauncherJar(jdtlsHome);
                if (launcherJar == null) {
                    return "JDTLS launcher JAR not found under: " + jdtlsHome + "/plugins/";
                }

                Path configDir = findConfigDir(jdtlsHome);
                if (configDir == null) {
                    return "JDTLS config directory not found in: " + jdtlsHome;
                }

                // Each JDTLS instance needs its own workspace-data directory for OSGi state
                Path workspaceData = Files.createTempDirectory("jdtls-workspace-data-");

                List<String> command = buildJdtlsCommand(launcherJar, configDir, workspaceData);
                LOG.info("Starting JDTLS with command: " + String.join(" ", command));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(false);
                jdtlsProcess = pb.start();
                LOG.info("JDTLS process started. PID: " + jdtlsProcess.pid());

                // Redirect stderr so the process does not block on full pipe buffer
                Thread stderrReader = new Thread(() -> {
                    try {
                        jdtlsProcess.getErrorStream().transferTo(System.err);
                    } catch (IOException ignored) {
                    }
                }, "jdtls-stderr");
                stderrReader.setDaemon(true);
                stderrReader.start();

                // Connect via LSP4J over the process stdin/stdout streams
                SimpleLanguageClient client = new SimpleLanguageClient(diagnosticsCache);
                Launcher<LanguageServer> launcher = new LSPLauncher.Builder<LanguageServer>()
                        .setLocalService(client)
                        .setRemoteInterface(LanguageServer.class)
                        .setInput(jdtlsProcess.getInputStream())
                        .setOutput(jdtlsProcess.getOutputStream())
                        .create();

                languageServer = launcher.getRemoteProxy();
                listenerFuture = launcher.startListening();
                connected = true;

                return "JDTLS started successfully. PID: " + jdtlsProcess.pid()
                        + ". Use initializeWorkspace(<path>) to open a Java project.";
            } catch (Exception e) {
                LOG.severe("Failed to start JDTLS: " + e.getMessage());
                return "Failed to start JDTLS: " + e.getMessage();
            }
        });
    }

    /**
     * Gracefully shut down the running JDTLS process.
     * Sends the LSP {@code shutdown} request first, then {@code exit}, and finally
     * force-destroys the process if it does not exit within the timeout.
     */
    public CompletableFuture<String> stopJdtls() {
        if (!isConnected()) {
            return CompletableFuture.completedFuture("JDTLS is not running.");
        }

        return CompletableFuture.supplyAsync(() -> {
            long pid = jdtlsProcess.pid();
            try {
                // Graceful LSP shutdown sequence
                languageServer.shutdown().get(5, TimeUnit.SECONDS);
                languageServer.exit();
                jdtlsProcess.waitFor(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warning("Graceful JDTLS shutdown failed, force-destroying: " + e.getMessage());
            }

            jdtlsProcess.destroyForcibly();
            if (listenerFuture != null) {
                listenerFuture.cancel(true);
            }

            connected = false;
            languageServer = null;
            jdtlsProcess = null;
            listenerFuture = null;
            currentWorkspaceRoot = null;
            openDocuments.clear();
            documentVersions.clear();
            diagnosticsCache.clear();

            return "JDTLS stopped. PID was: " + pid;
        });
    }

    /**
     * Register a workspace directory with JDTLS and perform the LSP initialize
     * handshake. When JDTLS is not yet running the workspace path is recorded so
     * it can be used once {@link #startJdtls()} is called.
     */
    public CompletableFuture<String> initializeWorkspace(String workspacePath) {
        // Validate workspace path
        File workspaceDir = new File(workspacePath);
        if (!workspaceDir.exists() || !workspaceDir.isDirectory()) {
            return CompletableFuture.completedFuture("Invalid workspace path: " + workspacePath);
        }

        // Check if it's a valid Java project (has pom.xml or build.gradle)
        File pomFile = new File(workspaceDir, "pom.xml");
        File gradleFile = new File(workspaceDir, "build.gradle");
        File gradleKtsFile = new File(workspaceDir, "build.gradle.kts");

        if (!pomFile.exists() && !gradleFile.exists() && !gradleKtsFile.exists()) {
            return CompletableFuture.completedFuture(
                    "Not a valid Java project. Missing pom.xml or build.gradle in: " + workspacePath);
        }

        currentWorkspaceRoot = workspacePath;
        String projectType = pomFile.exists() ? " (Maven project)" : " (Gradle project)";

        if (!isConnected()) {
            return CompletableFuture.completedFuture(
                    "Workspace initialized: " + workspacePath + projectType
                            + ". Note: JDTLS is not running. Use startJdtls() to start it.");
        }

        return sendInitializeRequest(workspacePath)
                .thenApply(result -> "Workspace initialized with JDTLS: " + workspacePath
                        + projectType + ". " + result);
    }

    /**
     * Return a human-readable status summary of the JDTLS process.
     */
    public CompletableFuture<String> getServerInfo() {
        if (!isConnected()) {
            if (currentWorkspaceRoot != null) {
                return CompletableFuture.completedFuture(
                        "Workspace set: " + currentWorkspaceRoot + ", but JDTLS is not running. Use startJdtls().");
            }
            return CompletableFuture.completedFuture(
                    "JDTLS is not running. Use startJdtls() to start it.");
        }

        return CompletableFuture.completedFuture(
                "JDTLS running. PID: " + jdtlsProcess.pid()
                        + ". Workspace: " + (currentWorkspaceRoot != null ? currentWorkspaceRoot : "not set"));
    }

    public String getDefaultTestWorkspace() {
        return System.getProperty("user.dir") + "/test-workspace";
    }

    // -------------------------------------------------------------------------
    // Core LSP tools
    // -------------------------------------------------------------------------

    /**
     * Extract document symbols (classes, methods, fields, etc.) from a Java file.
     * The file is opened in JDTLS via {@code textDocument/didOpen} if not already open.
     */
    public CompletableFuture<String> getSymbols(String filePath) {
        if (!isConnected()) {
            return CompletableFuture.completedFuture(
                    "JDTLS is not running. Use startJdtls() and initializeWorkspace() first.");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    return "File not found: " + filePath;
                }
                ensureFileOpen(filePath);
                String uri = file.toURI().toString();
                DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(uri));
                List<Either<SymbolInformation, DocumentSymbol>> symbols =
                        languageServer.getTextDocumentService().documentSymbol(params)
                                .get(10, TimeUnit.SECONDS);
                if (symbols == null || symbols.isEmpty()) {
                    return "No symbols found in: " + filePath;
                }
                StringBuilder sb = new StringBuilder("Symbols in " + filePath + ":\n");
                for (Either<SymbolInformation, DocumentSymbol> either : symbols) {
                    if (either.isRight()) {
                        DocumentSymbol sym = either.getRight();
                        sb.append("  ").append(sym.getKind()).append(" ").append(sym.getName())
                                .append(" [line ").append(sym.getRange().getStart().getLine() + 1).append("]\n");
                    } else {
                        SymbolInformation sym = either.getLeft();
                        sb.append("  ").append(sym.getKind()).append(" ").append(sym.getName()).append("\n");
                    }
                }
                return sb.toString();
            } catch (Exception e) {
                return "Error getting symbols: " + e.getMessage();
            }
        });
    }

    /**
     * Return code-completion suggestions at the given 0-based line/column position.
     */
    public CompletableFuture<String> getCompletions(String filePath, int line, int column) {
        if (!isConnected()) {
            return CompletableFuture.completedFuture(
                    "JDTLS is not running. Use startJdtls() and initializeWorkspace() first.");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    return "File not found: " + filePath;
                }
                ensureFileOpen(filePath);
                String uri = file.toURI().toString();
                CompletionParams params = new CompletionParams(
                        new TextDocumentIdentifier(uri), new Position(line, column));
                Either<List<CompletionItem>, CompletionList> result =
                        languageServer.getTextDocumentService().completion(params)
                                .get(10, TimeUnit.SECONDS);
                List<CompletionItem> items = result.isLeft()
                        ? result.getLeft()
                        : result.getRight().getItems();
                if (items == null || items.isEmpty()) {
                    return "No completions at line " + (line + 1) + ", col " + (column + 1)
                            + " in: " + filePath;
                }
                StringBuilder sb = new StringBuilder(
                        "Completions at line " + (line + 1) + ", col " + (column + 1)
                                + " in " + filePath + ":\n");
                items.stream().limit(20).forEach(item ->
                        sb.append("  ").append(item.getLabel())
                                .append(item.getDetail() != null ? " – " + item.getDetail() : "")
                                .append("\n"));
                if (items.size() > 20) {
                    sb.append("  ... and ").append(items.size() - 20).append(" more\n");
                }
                return sb.toString();
            } catch (Exception e) {
                return "Error getting completions: " + e.getMessage();
            }
        });
    }

    /**
     * Return compilation errors and warnings for a Java file.
     * Opens the file in JDTLS (if not already open) and waits up to 10 s for
     * diagnostics to be pushed back via {@code publishDiagnostics}.
     */
    public CompletableFuture<String> getDiagnostics(String filePath) {
        if (!isConnected()) {
            return CompletableFuture.completedFuture(
                    "JDTLS is not running. Use startJdtls() and initializeWorkspace() first.");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    return "File not found: " + filePath;
                }
                String uri = file.toURI().toString();
                ensureFileOpen(filePath);
                // Wait up to 10 s for JDTLS to push diagnostics
                long deadline = System.currentTimeMillis() + 10_000;
                while (!diagnosticsCache.containsKey(uri) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200);
                }
                List<Diagnostic> diags = diagnosticsCache.getOrDefault(uri, List.of());
                if (diags.isEmpty()) {
                    return "No diagnostics for: " + filePath;
                }
                StringBuilder sb = new StringBuilder("Diagnostics for " + filePath + ":\n");
                for (Diagnostic diag : diags) {
                    sb.append("  [").append(diag.getSeverity()).append("] line ")
                            .append(diag.getRange().getStart().getLine() + 1)
                            .append(": ").append(diag.getMessage()).append("\n");
                }
                return sb.toString();
            } catch (Exception e) {
                return "Error getting diagnostics: " + e.getMessage();
            }
        });
    }

    /**
     * Format a Java source file using JDTLS and write the result back to disk.
     */
    public CompletableFuture<String> formatCode(String filePath) {
        if (!isConnected()) {
            return CompletableFuture.completedFuture(
                    "JDTLS is not running. Use startJdtls() and initializeWorkspace() first.");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    return "File not found: " + filePath;
                }
                ensureFileOpen(filePath);
                String uri = file.toURI().toString();
                FormattingOptions options = new FormattingOptions();
                options.setTabSize(4);
                options.setInsertSpaces(true);
                DocumentFormattingParams params = new DocumentFormattingParams(
                        new TextDocumentIdentifier(uri), options);
                List<? extends TextEdit> edits = languageServer.getTextDocumentService()
                        .formatting(params).get(10, TimeUnit.SECONDS);
                if (edits == null || edits.isEmpty()) {
                    return "No formatting changes needed for: " + filePath;
                }
                String original = Files.readString(file.toPath());
                String formatted = applyTextEdits(original, edits);
                Files.writeString(file.toPath(), formatted);
                // Notify JDTLS of the updated content with an incremented version
                int nextVersion = documentVersions.merge(uri, 1, Integer::sum);
                VersionedTextDocumentIdentifier versionedId =
                        new VersionedTextDocumentIdentifier(uri, nextVersion);
                languageServer.getTextDocumentService().didChange(
                        new DidChangeTextDocumentParams(versionedId,
                                List.of(new TextDocumentContentChangeEvent(formatted))));
                return "File formatted: " + filePath + " (" + edits.size() + " edit(s) applied)";
            } catch (Exception e) {
                return "Error formatting code: " + e.getMessage();
            }
        });
    }

    /**
     * Return the definition location for a symbol at the given 0-based line/column.
     */
    public CompletableFuture<String> getDefinition(String filePath, int line, int column) {
        if (!isConnected()) {
            return CompletableFuture.completedFuture(
                    "JDTLS is not running. Use startJdtls() and initializeWorkspace() first.");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    return "File not found: " + filePath;
                }
                ensureFileOpen(filePath);
                String uri = file.toURI().toString();
                DefinitionParams params = new DefinitionParams(
                        new TextDocumentIdentifier(uri), new Position(line, column));
                Either<List<? extends Location>, List<? extends LocationLink>> result =
                        languageServer.getTextDocumentService().definition(params)
                                .get(10, TimeUnit.SECONDS);
                List<? extends Location> locations = result.isLeft() ? result.getLeft() : List.of();
                List<? extends LocationLink> links = result.isRight() ? result.getRight() : List.of();
                if (locations.isEmpty() && links.isEmpty()) {
                    return "No definition found at line " + (line + 1) + ", col " + (column + 1)
                            + " in: " + filePath;
                }
                StringBuilder sb = new StringBuilder(
                        "Definition at line " + (line + 1) + ", col " + (column + 1)
                                + " in " + filePath + ":\n");
                for (Location loc : locations) {
                    sb.append("  ").append(loc.getUri())
                            .append(" line ").append(loc.getRange().getStart().getLine() + 1)
                            .append("\n");
                }
                for (LocationLink link : links) {
                    sb.append("  ").append(link.getTargetUri())
                            .append(" line ").append(link.getTargetSelectionRange().getStart().getLine() + 1)
                            .append("\n");
                }
                return sb.toString();
            } catch (Exception e) {
                return "Error getting definition: " + e.getMessage();
            }
        });
    }

    // -------------------------------------------------------------------------
    // LSP protocol helpers
    // -------------------------------------------------------------------------

    private CompletableFuture<String> sendInitializeRequest(String workspacePath) {
        InitializeParams params = new InitializeParams();
        params.setRootUri(new File(workspacePath).toURI().toString());
        params.setRootPath(workspacePath);

        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setTextDocument(new TextDocumentClientCapabilities());
        WorkspaceClientCapabilities workspaceCaps = new WorkspaceClientCapabilities();
        workspaceCaps.setApplyEdit(true);
        capabilities.setWorkspace(workspaceCaps);
        params.setCapabilities(capabilities);

        return languageServer.initialize(params)
                .thenApply(result -> {
                    languageServer.initialized(new InitializedParams());
                    ServerCapabilities caps = result.getCapabilities();
                    return "LSP handshake complete. Text document sync: " + caps.getTextDocumentSync();
                })
                .exceptionally(e -> "LSP initialization error: " + e.getMessage());
    }

    /**
     * Open a file in JDTLS via {@code textDocument/didOpen} if it has not been opened yet.
     * Subsequent LSP requests (symbols, completions, definition, formatting) require
     * the file to be open first.
     */
    private void ensureFileOpen(String filePath) throws IOException {
        String uri = new File(filePath).toURI().toString();
        if (openDocuments.add(uri)) {
            String content = Files.readString(Paths.get(filePath));
            TextDocumentItem textDoc = new TextDocumentItem(uri, "java", 1, content);
            languageServer.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(textDoc));
            documentVersions.put(uri, 1);
        }
    }

    /**
     * Apply a list of {@link TextEdit}s (as returned by the LSP formatting request) to
     * {@code content}. Edits are applied in reverse document order so that earlier
     * character offsets remain valid after each replacement.
     */
    String applyTextEdits(String content, List<? extends TextEdit> edits) {
        List<TextEdit> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> {
            int lineCmp = Integer.compare(
                    b.getRange().getStart().getLine(),
                    a.getRange().getStart().getLine());
            if (lineCmp != 0) {
                return lineCmp;
            }
            return Integer.compare(
                    b.getRange().getStart().getCharacter(),
                    a.getRange().getStart().getCharacter());
        });
        List<Integer> lineOffsets = computeLineOffsets(content);
        StringBuilder result = new StringBuilder(content);
        for (TextEdit edit : sorted) {
            int start = lineOffsets.get(edit.getRange().getStart().getLine())
                    + edit.getRange().getStart().getCharacter();
            int end = lineOffsets.get(edit.getRange().getEnd().getLine())
                    + edit.getRange().getEnd().getCharacter();
            result.replace(start, end, edit.getNewText());
        }
        return result.toString();
    }

    private List<Integer> computeLineOffsets(String content) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        return offsets;
    }

    // -------------------------------------------------------------------------
    // JDTLS discovery helpers
    // -------------------------------------------------------------------------

    String findJdtlsHome() {
        // 1. Explicitly configured path
        if (jdtlsInstallPath.isPresent() && !jdtlsInstallPath.get().isBlank()) {
            String path = jdtlsInstallPath.get();
            if (new File(path).isDirectory()) {
                return path;
            }
            LOG.warning("Configured jdtls.install.path does not exist: " + path);
        }

        // 2. 'jdtls' wrapper script on PATH → derive home from script location
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "jdtls");
            Process p = pb.start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                String scriptPath = new String(p.getInputStream().readAllBytes()).trim();
                File scriptFile = new File(scriptPath).getCanonicalFile();
                File binDir = scriptFile.getParentFile();
                if (binDir != null && binDir.getName().equals("bin")) {
                    return binDir.getParent();
                }
                if (binDir != null) {
                    return binDir.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
        }

        // 3. Common installation paths
        String[] commonPaths = {
            System.getProperty("user.home") + "/.local/share/jdtls",
            "/usr/local/jdtls",
            "/opt/jdtls"
        };
        for (String path : commonPaths) {
            if (new File(path).isDirectory()) {
                return path;
            }
        }

        return null;
    }

    Path findLauncherJar(String jdtlsHome) throws IOException {
        Path pluginsDir = Paths.get(jdtlsHome, "plugins");
        if (!Files.isDirectory(pluginsDir)) {
            return null;
        }
        try (var stream = Files.find(pluginsDir, 1,
                (path, attrs) -> path.getFileName().toString()
                        .startsWith("org.eclipse.equinox.launcher_")
                        && path.getFileName().toString().endsWith(".jar"))) {
            return stream.findFirst().orElse(null);
        }
    }

    Path findConfigDir(String jdtlsHome) {
        String os = System.getProperty("os.name").toLowerCase();
        String configDirName;
        if (os.contains("mac") || os.contains("darwin")) {
            configDirName = "config_mac";
        } else if (os.contains("win")) {
            configDirName = "config_win";
        } else {
            configDirName = "config_linux";
        }
        Path configDir = Paths.get(jdtlsHome, configDirName);
        return Files.isDirectory(configDir) ? configDir : null;
    }

    List<String> buildJdtlsCommand(Path launcherJar, Path configDir, Path workspaceData) {
        List<String> command = new ArrayList<>();
        // Use the same java executable that is running this JVM
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        command.add("-Dosgi.bundles.defaultStartLevel=4");
        command.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        command.add("-Dlog.level=ALL");
        command.add("-Xmx1G");
        command.add("--add-modules=ALL-SYSTEM");
        command.add("--add-opens");
        command.add("java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.lang=ALL-UNNAMED");
        command.add("-jar");
        command.add(launcherJar.toString());
        command.add("-configuration");
        command.add(configDir.toString());
        command.add("-data");
        command.add(workspaceData.toString());
        return command;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PreDestroy
    void cleanup() {
        if (isConnected()) {
            stopJdtls();
        }
    }

    // -------------------------------------------------------------------------
    // Language client implementation
    // -------------------------------------------------------------------------

    private static class SimpleLanguageClient implements LanguageClient {

        private final Map<String, List<Diagnostic>> diagnosticsCache;

        SimpleLanguageClient(Map<String, List<Diagnostic>> diagnosticsCache) {
            this.diagnosticsCache = diagnosticsCache;
        }

        @Override
        public void telemetryEvent(Object object) {
            // no-op
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            diagnosticsCache.put(diagnostics.getUri(), diagnostics.getDiagnostics());
            LOG.info("Diagnostics for " + diagnostics.getUri() + ": "
                    + diagnostics.getDiagnostics().size() + " issue(s)");
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            LOG.info("jdtls: " + messageParams.getMessage());
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
            LOG.fine("jdtls log: " + message.getMessage());
        }

        private static final Logger LOG =
                Logger.getLogger(SimpleLanguageClient.class.getName());
    }
}