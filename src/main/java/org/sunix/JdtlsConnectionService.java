package org.sunix;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
                SimpleLanguageClient client = new SimpleLanguageClient();
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

        @Override
        public void telemetryEvent(Object object) {
            // no-op
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
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