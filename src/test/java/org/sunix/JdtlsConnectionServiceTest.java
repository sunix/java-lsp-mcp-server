package org.sunix;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class JdtlsConnectionServiceTest {

    @Inject
    JdtlsConnectionService service;

    @BeforeEach
    void ensureStopped() {
        // Make sure JDTLS is not running before each test
        if (service.isConnected()) {
            try {
                service.stopJdtls().get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    // -------------------------------------------------------------------------
    // isConnected / initial state
    // -------------------------------------------------------------------------

    @Test
    void initiallyNotConnected() {
        assertFalse(service.isConnected(),
                "Service should not be connected before startJdtls() is called");
    }

    @Test
    void initiallyNoWorkspace() {
        assertNull(service.getCurrentWorkspace(),
                "No workspace should be set initially");
    }

    // -------------------------------------------------------------------------
    // getDefaultTestWorkspace
    // -------------------------------------------------------------------------

    @Test
    void defaultTestWorkspaceEndsWithTestWorkspace() {
        String path = service.getDefaultTestWorkspace();
        assertNotNull(path);
        assertTrue(path.endsWith("test-workspace"),
                "Default test workspace should end with 'test-workspace', got: " + path);
    }

    // -------------------------------------------------------------------------
    // initializeWorkspace – validation without JDTLS running
    // -------------------------------------------------------------------------

    @Test
    void initializeWorkspaceInvalidPath() throws Exception {
        String result = service.initializeWorkspace("/nonexistent/path/xyz")
                .get(5, TimeUnit.SECONDS);
        assertTrue(result.startsWith("Invalid workspace path"),
                "Expected invalid path message, got: " + result);
    }

    @Test
    void initializeWorkspaceNotAJavaProject() throws Exception {
        Path tmpDir = Files.createTempDirectory("not-a-java-project");
        try {
            String result = service.initializeWorkspace(tmpDir.toString())
                    .get(5, TimeUnit.SECONDS);
            assertTrue(result.contains("Not a valid Java project"),
                    "Expected 'Not a valid Java project' message, got: " + result);
        } finally {
            Files.delete(tmpDir);
        }
    }

    @Test
    void initializeWorkspaceValidMavenProject() throws Exception {
        Path tmpDir = Files.createTempDirectory("maven-project");
        Files.createFile(tmpDir.resolve("pom.xml"));
        try {
            String result = service.initializeWorkspace(tmpDir.toString())
                    .get(5, TimeUnit.SECONDS);
            assertTrue(result.contains("Maven project"),
                    "Expected Maven project message, got: " + result);
            assertTrue(result.contains(tmpDir.toString()),
                    "Expected workspace path in message, got: " + result);
        } finally {
            Files.delete(tmpDir.resolve("pom.xml"));
            Files.delete(tmpDir);
        }
    }

    @Test
    void initializeWorkspaceValidGradleProject() throws Exception {
        Path tmpDir = Files.createTempDirectory("gradle-project");
        Files.createFile(tmpDir.resolve("build.gradle"));
        try {
            String result = service.initializeWorkspace(tmpDir.toString())
                    .get(5, TimeUnit.SECONDS);
            assertTrue(result.contains("Gradle project"),
                    "Expected Gradle project message, got: " + result);
        } finally {
            Files.delete(tmpDir.resolve("build.gradle"));
            Files.delete(tmpDir);
        }
    }

    @Test
    void initializeWorkspaceSetsCurrentWorkspace() throws Exception {
        Path tmpDir = Files.createTempDirectory("maven-project2");
        Files.createFile(tmpDir.resolve("pom.xml"));
        try {
            service.initializeWorkspace(tmpDir.toString()).get(5, TimeUnit.SECONDS);
            assertEquals(tmpDir.toString(), service.getCurrentWorkspace());
        } finally {
            Files.delete(tmpDir.resolve("pom.xml"));
            Files.delete(tmpDir);
        }
    }

    @Test
    void initializeWorkspaceWhenJdtlsNotRunningMentionsStartJdtls() throws Exception {
        Path tmpDir = Files.createTempDirectory("maven-project3");
        Files.createFile(tmpDir.resolve("pom.xml"));
        try {
            String result = service.initializeWorkspace(tmpDir.toString())
                    .get(5, TimeUnit.SECONDS);
            assertTrue(result.contains("startJdtls"),
                    "Should suggest startJdtls() when JDTLS is not running, got: " + result);
        } finally {
            Files.delete(tmpDir.resolve("pom.xml"));
            Files.delete(tmpDir);
        }
    }

    // -------------------------------------------------------------------------
    // getServerInfo – without JDTLS running
    // -------------------------------------------------------------------------

    @Test
    void getServerInfoWhenNotConnected() throws Exception {
        String result = service.getServerInfo().get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("not running") || result.contains("not connected"),
                "Should indicate JDTLS is not running, got: " + result);
    }

    // -------------------------------------------------------------------------
    // stopJdtls – when not running
    // -------------------------------------------------------------------------

    @Test
    void stopJdtlsWhenNotRunning() throws Exception {
        String result = service.stopJdtls().get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("not running"),
                "Should say JDTLS is not running, got: " + result);
    }

    // -------------------------------------------------------------------------
    // JDTLS discovery helpers
    // -------------------------------------------------------------------------

    @Test
    void findLauncherJarReturnsNullForMissingDir() throws IOException {
        assertNull(service.findLauncherJar("/nonexistent/jdtls-home"));
    }

    @Test
    void buildJdtlsCommandContainsRequiredFlags() throws IOException {
        Path fakeJar = Files.createTempFile("org.eclipse.equinox.launcher_", ".jar");
        Path fakeConfig = Files.createTempDirectory("config_linux");
        Path fakeData = Files.createTempDirectory("jdtls-data");
        try {
            List<String> cmd = service.buildJdtlsCommand(fakeJar, fakeConfig, fakeData);
            String joined = String.join(" ", cmd);
            assertTrue(joined.contains("-Declipse.application=org.eclipse.jdt.ls.core.id1"),
                    "Command missing Eclipse application flag");
            assertTrue(joined.contains("-jar"), "Command missing -jar flag");
            assertTrue(joined.contains("-configuration"), "Command missing -configuration flag");
            assertTrue(joined.contains("-data"), "Command missing -data flag");
            assertTrue(joined.contains("--add-modules=ALL-SYSTEM"),
                    "Command missing --add-modules flag");
        } finally {
            Files.deleteIfExists(fakeJar);
            Files.delete(fakeConfig);
            Files.delete(fakeData);
        }
    }

    // -------------------------------------------------------------------------
    // Core LSP tools – not-connected / file-not-found guards
    // -------------------------------------------------------------------------

    @Test
    void getSymbolsWhenNotConnected() throws Exception {
        String result = service.getSymbols("/any/file.java").get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("not running"),
                "Should indicate JDTLS is not running, got: " + result);
    }

    @Test
    void getCompletionsWhenNotConnected() throws Exception {
        String result = service.getCompletions("/any/file.java", 0, 0).get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("not running"),
                "Should indicate JDTLS is not running, got: " + result);
    }

    @Test
    void getDiagnosticsWhenNotConnected() throws Exception {
        String result = service.getDiagnostics("/any/file.java").get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("not running"),
                "Should indicate JDTLS is not running, got: " + result);
    }

    @Test
    void formatCodeWhenNotConnected() throws Exception {
        String result = service.formatCode("/any/file.java").get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("not running"),
                "Should indicate JDTLS is not running, got: " + result);
    }

    @Test
    void getDefinitionWhenNotConnected() throws Exception {
        String result = service.getDefinition("/any/file.java", 0, 0).get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("not running"),
                "Should indicate JDTLS is not running, got: " + result);
    }

    // -------------------------------------------------------------------------
    // JDTLS auto-download helpers
    // -------------------------------------------------------------------------

    @Test
    void getManagedJdtlsDirEndsWithExpectedPath() {
        String dir = service.getManagedJdtlsDir();
        assertNotNull(dir);
        assertTrue(dir.endsWith("java-lsp-mcp-server/jdtls"),
                "Managed dir should end with 'java-lsp-mcp-server/jdtls', got: " + dir);
    }

    @Test
    void parseLatestVersionReturnsHighestVersion() {
        String html = "<html>"
                + "<a href=\"1.9.0/\">1.9.0/</a>"
                + "<a href=\"1.38.0/\">1.38.0/</a>"
                + "<a href=\"1.40.0/\">1.40.0/</a>"
                + "<a href=\"1.10.0/\">1.10.0/</a>"
                + "</html>";
        assertEquals("1.40.0", service.parseLatestVersion(html));
    }

    @Test
    void parseLatestVersionReturnsNullWhenNoVersions() {
        assertNull(service.parseLatestVersion("<html><body>No versions here</body></html>"));
    }

    @Test
    void parseDownloadUrlFindsCorrectTarGz() {
        String html = "<html>"
                + "<a href=\"jdt-language-server-1.40.0-202503201301.tar.gz\">"
                + "jdt-language-server-1.40.0-202503201301.tar.gz</a>"
                + "</html>";
        String baseUrl = "https://download.eclipse.org/jdtls/milestones/1.40.0/";
        String url = service.parseDownloadUrl(html, "1.40.0", baseUrl);
        assertEquals(baseUrl + "jdt-language-server-1.40.0-202503201301.tar.gz", url);
    }

    @Test
    void parseDownloadUrlReturnsNullWhenNotFound() {
        String html = "<html><body>nothing here</body></html>";
        assertNull(service.parseDownloadUrl(html, "1.40.0",
                "https://download.eclipse.org/jdtls/milestones/1.40.0/"));
    }

    @Test
    void compareVersionsCorrectOrder() {
        assertTrue(service.compareVersions("1.40.0", "1.9.0") > 0,
                "1.40.0 should be greater than 1.9.0");
        assertTrue(service.compareVersions("1.9.0", "1.40.0") < 0,
                "1.9.0 should be less than 1.40.0");
        assertEquals(0, service.compareVersions("1.40.0", "1.40.0"),
                "Same versions should compare as equal");
        assertTrue(service.compareVersions("2.0.0", "1.99.99") > 0,
                "2.0.0 should be greater than 1.99.99");
    }

    @Test
    void downloadAndInstallJdtlsReportsAlreadyInstalledWhenPresent() throws Exception {
        // Create a fake managed installation so the "already installed" branch is hit.
        String managedDir = service.getManagedJdtlsDir();
        Path pluginsDir = Path.of(managedDir, "plugins");
        Path fakeJar = pluginsDir.resolve("org.eclipse.equinox.launcher_1.0.0.jar");
        Files.createDirectories(pluginsDir);
        Files.createFile(fakeJar);
        try {
            String result = service.downloadAndInstallJdtls().get(10, TimeUnit.SECONDS);
            assertTrue(result.contains("already installed"),
                    "Should report already installed, got: " + result);
        } finally {
            Files.deleteIfExists(fakeJar);
            Files.deleteIfExists(pluginsDir);
            Files.deleteIfExists(Path.of(managedDir));
            // best-effort cleanup of parent dirs (failure here should not mask test failures)
            Path parentDir = Path.of(managedDir).getParent();
            if (parentDir != null) {
                try {
                    Files.deleteIfExists(parentDir);
                } catch (IOException e) {
                    // Ignore: parent may not be empty if other content exists there
                }
            }
        }
    }

    @Test
    void startJdtlsWhenNotInstalledAndAutoDownloadDisabled() throws Exception {
        // Auto-download is disabled in tests via %test.jdtls.auto.download=false.
        // Verify that when JDTLS is not present the helpful error message is returned.
        String home = service.findJdtlsHome();
        org.junit.jupiter.api.Assumptions.assumeTrue(home == null,
                "JDTLS is installed at " + home + "; skipping 'not installed' test");

        String result = service.startJdtls().get(30, TimeUnit.SECONDS);
        assertTrue(result.contains("not found") || result.contains("not installed")
                        || result.contains("JDTLS not found"),
                "Should report JDTLS not found when auto-download is disabled, got: " + result);
    }

    // -------------------------------------------------------------------------
    // applyTextEdits helper
    // -------------------------------------------------------------------------

    @Test
    void applyTextEditsReplacesRange() {
        String content = "hello world\n";
        // Replace "world" (chars 6-11 on line 0) with "Java"
        org.eclipse.lsp4j.TextEdit edit = new org.eclipse.lsp4j.TextEdit(
                new org.eclipse.lsp4j.Range(
                        new org.eclipse.lsp4j.Position(0, 6),
                        new org.eclipse.lsp4j.Position(0, 11)),
                "Java");
        String result = service.applyTextEdits(content, List.of(edit));
        assertEquals("hello Java\n", result);
    }

    @Test
    void applyTextEditsMultipleEditsAppliedCorrectly() {
        String content = "aaa\nbbb\nccc\n";
        // Replace "bbb" on line 1, then replace "aaa" on line 0 (reverse order expected)
        org.eclipse.lsp4j.TextEdit edit1 = new org.eclipse.lsp4j.TextEdit(
                new org.eclipse.lsp4j.Range(
                        new org.eclipse.lsp4j.Position(1, 0),
                        new org.eclipse.lsp4j.Position(1, 3)),
                "BBB");
        org.eclipse.lsp4j.TextEdit edit2 = new org.eclipse.lsp4j.TextEdit(
                new org.eclipse.lsp4j.Range(
                        new org.eclipse.lsp4j.Position(0, 0),
                        new org.eclipse.lsp4j.Position(0, 3)),
                "AAA");
        String result = service.applyTextEdits(content, List.of(edit1, edit2));
        assertEquals("AAA\nBBB\nccc\n", result);
    }
}
