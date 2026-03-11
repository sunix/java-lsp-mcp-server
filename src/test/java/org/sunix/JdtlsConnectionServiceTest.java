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
    // startJdtls – when JDTLS is not installed
    // -------------------------------------------------------------------------

    @Test
    void startJdtlsWhenNotInstalled() throws Exception {
        // This test verifies graceful failure when JDTLS is not on the system.
        // If JDTLS happens to be installed the test is skipped.
        String home = service.findJdtlsHome();
        org.junit.jupiter.api.Assumptions.assumeTrue(home == null,
                "JDTLS is installed at " + home + "; skipping 'not installed' test");

        String result = service.startJdtls().get(30, TimeUnit.SECONDS);
        assertTrue(result.contains("not found") || result.contains("not installed")
                        || result.contains("JDTLS not found"),
                "Should report JDTLS not found, got: " + result);
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
}
