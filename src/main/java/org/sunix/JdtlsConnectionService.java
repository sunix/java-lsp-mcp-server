package org.sunix;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * In-process Java analysis service using JavaParser.
 * <p>
 * Runs inside the same JVM as the MCP server — no external JDTLS process is
 * needed.  Provides workspace management, syntax diagnostics and symbol
 * extraction for Java source files.  More features (type resolution, code
 * completion, …) can be added incrementally.
 */
@ApplicationScoped
public class JdtlsConnectionService {

    private static final Logger LOG = Logger.getLogger(JdtlsConnectionService.class.getName());

    private final JavaParser javaParser;
    private boolean initialized = false;
    private String currentWorkspaceRoot;

    public JdtlsConnectionService() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(config);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getCurrentWorkspace() {
        return currentWorkspaceRoot;
    }

    // -------------------------------------------------------------------------
    // Workspace management
    // -------------------------------------------------------------------------

    /**
     * Validate and register a workspace directory.  The directory must exist
     * and contain a {@code pom.xml} or {@code build.gradle(.kts)} file.
     */
    public String initializeWorkspace(String workspacePath) {
        File workspaceDir = new File(workspacePath);
        if (!workspaceDir.exists() || !workspaceDir.isDirectory()) {
            return "Invalid workspace path: " + workspacePath;
        }

        File pomFile = new File(workspaceDir, "pom.xml");
        File gradleFile = new File(workspaceDir, "build.gradle");
        File gradleKtsFile = new File(workspaceDir, "build.gradle.kts");

        if (!pomFile.exists() && !gradleFile.exists() && !gradleKtsFile.exists()) {
            return "Not a valid Java project. Missing pom.xml or build.gradle in: " + workspacePath;
        }

        currentWorkspaceRoot = workspacePath;
        initialized = true;
        String projectType = pomFile.exists() ? " (Maven project)" : " (Gradle project)";

        List<Path> javaFiles = listJavaFiles();
        return "Workspace initialized: " + workspacePath + projectType
                + ". Found " + javaFiles.size() + " Java source file(s).";
    }

    // -------------------------------------------------------------------------
    // Server info
    // -------------------------------------------------------------------------

    /**
     * Return a human-readable status summary of the analysis service.
     */
    public String getServerInfo() {
        if (!initialized) {
            return "Java analysis service is ready. No workspace set — use initializeWorkspace(<path>).";
        }
        List<Path> javaFiles = listJavaFiles();
        return "Java analysis service running (in-process). Workspace: " + currentWorkspaceRoot
                + ". Java files: " + javaFiles.size() + ".";
    }

    // -------------------------------------------------------------------------
    // Diagnostics (syntax-level)
    // -------------------------------------------------------------------------

    /**
     * Parse a Java source file and return any syntax problems found.
     *
     * @param filePath absolute path, or relative to the workspace root
     */
    public String getDiagnostics(String filePath) {
        Path path = resolveFilePath(filePath);
        if (path == null) {
            return "File not found: " + filePath;
        }

        try {
            String source = Files.readString(path);
            ParseResult<CompilationUnit> result = javaParser.parse(source);

            if (result.isSuccessful() && result.getProblems().isEmpty()) {
                return "No issues found in: " + path.getFileName();
            }

            StringBuilder sb = new StringBuilder();
            sb.append(result.getProblems().size())
              .append(" issue(s) in ").append(path.getFileName()).append(":\n");
            result.getProblems().forEach(p ->
                    sb.append("  ").append(p.getVerboseMessage()).append("\n"));
            return sb.toString().stripTrailing();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Symbol extraction
    // -------------------------------------------------------------------------

    /**
     * Parse a Java source file and return the declared symbols (packages,
     * types, methods, fields, constructors).
     *
     * @param filePath absolute path, or relative to the workspace root
     */
    public String getSymbols(String filePath) {
        Path path = resolveFilePath(filePath);
        if (path == null) {
            return "File not found: " + filePath;
        }

        try {
            String source = Files.readString(path);
            ParseResult<CompilationUnit> result = javaParser.parse(source);

            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return "Cannot extract symbols — file has syntax errors. "
                        + "Use getDiagnostics() to see details.";
            }

            CompilationUnit cu = result.getResult().get();
            StringBuilder sb = new StringBuilder();
            sb.append("Symbols in ").append(path.getFileName()).append(":\n");

            cu.getPackageDeclaration().ifPresent(p ->
                    sb.append("  Package: ").append(p.getName()).append("\n"));

            cu.getImports().forEach(i ->
                    sb.append("  Import: ").append(i.getName())
                      .append(i.isAsterisk() ? ".*" : "").append("\n"));

            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                    sb.append("  ").append(n.isInterface() ? "Interface" : "Class")
                      .append(": ").append(n.getNameAsString()).append("\n");
                    super.visit(n, arg);
                }

                @Override
                public void visit(EnumDeclaration n, Void arg) {
                    sb.append("  Enum: ").append(n.getNameAsString()).append("\n");
                    super.visit(n, arg);
                }

                @Override
                public void visit(FieldDeclaration n, Void arg) {
                    n.getVariables().forEach(v ->
                            sb.append("    Field: ").append(v.getNameAsString())
                              .append(" : ").append(v.getType()).append("\n"));
                }

                @Override
                public void visit(ConstructorDeclaration n, Void arg) {
                    sb.append("    Constructor: ").append(n.getNameAsString())
                      .append("(").append(n.getParameters()).append(")\n");
                }

                @Override
                public void visit(MethodDeclaration n, Void arg) {
                    sb.append("    Method: ").append(n.getNameAsString())
                      .append("(").append(n.getParameters()).append(")")
                      .append(" : ").append(n.getType()).append("\n");
                }
            }, null);

            return sb.toString().stripTrailing();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Workspace helpers
    // -------------------------------------------------------------------------

    public String getDefaultTestWorkspace() {
        return System.getProperty("user.dir") + "/test-workspace";
    }

    /**
     * List all {@code .java} files under the workspace's source directories.
     */
    List<Path> listJavaFiles() {
        if (currentWorkspaceRoot == null) {
            return List.of();
        }
        PathMatcher matcher = Path.of(currentWorkspaceRoot).getFileSystem().getPathMatcher("glob:**.java");
        List<Path> files = new ArrayList<>();
        String[] sourceDirs = {
            "src/main/java", "src/test/java", "src"
        };
        for (String dir : sourceDirs) {
            Path srcDir = Path.of(currentWorkspaceRoot, dir);
            if (Files.isDirectory(srcDir)) {
                try (Stream<Path> walk = Files.walk(srcDir)) {
                    walk.filter(Files::isRegularFile)
                        .filter(matcher::matches)
                        .forEach(files::add);
                } catch (IOException e) {
                    LOG.warning("Error scanning " + srcDir + ": " + e.getMessage());
                }
            }
        }
        return files;
    }

    /**
     * Resolve a file path that is either absolute or relative to the workspace.
     */
    Path resolveFilePath(String filePath) {
        Path path = Path.of(filePath);
        if (Files.isRegularFile(path)) {
            return path;
        }
        if (currentWorkspaceRoot != null) {
            Path resolved = Path.of(currentWorkspaceRoot).resolve(filePath);
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        return null;
    }
}