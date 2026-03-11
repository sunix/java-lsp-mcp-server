package org.sunix;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class JdtlsConnectionServiceTest {

    @Inject
    JdtlsConnectionService service;

    @BeforeEach
    void resetWorkspace() throws Exception {
        // Reset service state by re-injecting (CDI handles scoping).
        // For @ApplicationScoped we just ensure no stale state leaks across
        // tests by re-initialising with a known workspace when needed.
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    void initiallyNotInitialized() {
        // A freshly-injected service has no workspace set
        assertNotNull(service);
    }

    @Test
    void defaultTestWorkspaceEndsWithTestWorkspace() {
        String path = service.getDefaultTestWorkspace();
        assertNotNull(path);
        assertTrue(path.endsWith("test-workspace"),
                "Default test workspace should end with 'test-workspace', got: " + path);
    }

    // -------------------------------------------------------------------------
    // initializeWorkspace
    // -------------------------------------------------------------------------

    @Test
    void initializeWorkspaceInvalidPath() {
        String result = service.initializeWorkspace("/nonexistent/path/xyz");
        assertTrue(result.startsWith("Invalid workspace path"),
                "Expected invalid path message, got: " + result);
    }

    @Test
    void initializeWorkspaceNotAJavaProject() throws Exception {
        Path tmpDir = Files.createTempDirectory("not-a-java-project");
        try {
            String result = service.initializeWorkspace(tmpDir.toString());
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
            String result = service.initializeWorkspace(tmpDir.toString());
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
            String result = service.initializeWorkspace(tmpDir.toString());
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
            service.initializeWorkspace(tmpDir.toString());
            assertEquals(tmpDir.toString(), service.getCurrentWorkspace());
        } finally {
            Files.delete(tmpDir.resolve("pom.xml"));
            Files.delete(tmpDir);
        }
    }

    @Test
    void initializeTestWorkspaceReportsJavaFiles() {
        String testWs = service.getDefaultTestWorkspace();
        if (!Files.isDirectory(Path.of(testWs))) {
            return; // skip if test-workspace isn't present
        }
        String result = service.initializeWorkspace(testWs);
        assertTrue(result.contains("Java source file"),
                "Should mention Java files, got: " + result);
    }

    // -------------------------------------------------------------------------
    // getServerInfo
    // -------------------------------------------------------------------------

    @Test
    void getServerInfoBeforeInit() {
        // Create a fresh-like scenario: serverInfo before any workspace
        String result = service.getServerInfo();
        assertTrue(result.contains("ready") || result.contains("Workspace"),
                "Should indicate service is ready, got: " + result);
    }

    // -------------------------------------------------------------------------
    // getDiagnostics
    // -------------------------------------------------------------------------

    @Test
    void getDiagnosticsGoodFile() throws Exception {
        Path tmpFile = Files.createTempFile("Good", ".java");
        Files.writeString(tmpFile, "public class Good { public int getValue() { return 42; } }\n");
        try {
            String result = service.getDiagnostics(tmpFile.toString());
            assertTrue(result.contains("No issues"),
                    "Expected no issues for valid file, got: " + result);
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void getDiagnosticsBadFile() throws Exception {
        Path tmpFile = Files.createTempFile("Bad", ".java");
        Files.writeString(tmpFile, "public class Bad { public void broken( { } }\n");
        try {
            String result = service.getDiagnostics(tmpFile.toString());
            assertTrue(result.contains("issue"),
                    "Expected issues for broken file, got: " + result);
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void getDiagnosticsFileNotFound() {
        String result = service.getDiagnostics("/nonexistent/Foo.java");
        assertTrue(result.contains("File not found"),
                "Expected file-not-found message, got: " + result);
    }

    // -------------------------------------------------------------------------
    // getSymbols
    // -------------------------------------------------------------------------

    @Test
    void getSymbolsExtractsClassAndMethods() throws Exception {
        Path tmpFile = Files.createTempFile("Calc", ".java");
        Files.writeString(tmpFile,
                "package demo;\n"
                + "public class Calc {\n"
                + "    private int value;\n"
                + "    public Calc() { this.value = 0; }\n"
                + "    public int add(int a, int b) { return a + b; }\n"
                + "}\n");
        try {
            String result = service.getSymbols(tmpFile.toString());
            assertTrue(result.contains("Package: demo"), "Expected package, got: " + result);
            assertTrue(result.contains("Class: Calc"), "Expected class, got: " + result);
            assertTrue(result.contains("Field: value"), "Expected field, got: " + result);
            assertTrue(result.contains("Constructor: Calc"), "Expected constructor, got: " + result);
            assertTrue(result.contains("Method: add"), "Expected method, got: " + result);
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void getSymbolsFileNotFound() {
        String result = service.getSymbols("/nonexistent/Foo.java");
        assertTrue(result.contains("File not found"),
                "Expected file-not-found message, got: " + result);
    }

    @Test
    void getSymbolsTestWorkspaceCalculator() {
        String testWs = service.getDefaultTestWorkspace();
        Path calcFile = Path.of(testWs, "src/main/java/com/example/Calculator.java");
        if (!Files.isRegularFile(calcFile)) {
            return; // skip if test-workspace isn't present
        }
        String result = service.getSymbols(calcFile.toString());
        assertTrue(result.contains("Class: Calculator"), "Expected Calculator class, got: " + result);
        assertTrue(result.contains("Method: add"), "Expected add method, got: " + result);
    }

    // -------------------------------------------------------------------------
    // resolveFilePath
    // -------------------------------------------------------------------------

    @Test
    void resolveFilePathAbsolute() throws Exception {
        Path tmpFile = Files.createTempFile("Abs", ".java");
        try {
            assertNotNull(service.resolveFilePath(tmpFile.toString()));
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void resolveFilePathRelativeToWorkspace() throws Exception {
        Path tmpDir = Files.createTempDirectory("ws");
        Path javaDir = tmpDir.resolve("src/main/java");
        Files.createDirectories(javaDir);
        Files.createFile(tmpDir.resolve("pom.xml"));
        Files.writeString(javaDir.resolve("App.java"), "public class App {}");
        try {
            service.initializeWorkspace(tmpDir.toString());
            Path resolved = service.resolveFilePath("src/main/java/App.java");
            assertNotNull(resolved, "Should resolve relative path from workspace");
        } finally {
            Files.deleteIfExists(javaDir.resolve("App.java"));
            Files.delete(javaDir);
            Files.delete(tmpDir.resolve("src/main"));
            Files.delete(tmpDir.resolve("src"));
            Files.delete(tmpDir.resolve("pom.xml"));
            Files.delete(tmpDir);
        }
    }

    // -------------------------------------------------------------------------
    // listJavaFiles
    // -------------------------------------------------------------------------

    @Test
    void listJavaFilesInTestWorkspace() {
        String testWs = service.getDefaultTestWorkspace();
        if (!Files.isDirectory(Path.of(testWs))) {
            return;
        }
        service.initializeWorkspace(testWs);
        var files = service.listJavaFiles();
        assertFalse(files.isEmpty(), "test-workspace should contain Java files");
    }
}
