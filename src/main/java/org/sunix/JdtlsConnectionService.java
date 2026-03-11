package org.sunix;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * In-process Java analysis service using Eclipse JDT Core APIs.
 * <p>
 * Runs the Eclipse Java compiler and DOM AST parser inside the same JVM as the
 * MCP server — no external JDTLS process is needed.  Uses the same JDT Core
 * libraries that power Eclipse IDE and JDTLS but without the OSGi/LSP overhead.
 * Provides workspace management, diagnostics and symbol extraction for Java
 * source files.  More features (type resolution, code completion, …) can be
 * added incrementally.
 */
@ApplicationScoped
public class JdtlsConnectionService {

    private static final Logger LOG = Logger.getLogger(JdtlsConnectionService.class.getName());

    /** Compiler options shared across all parse calls. */
    private static final Map<String, String> COMPILER_OPTIONS = Map.of(
            JavaCore.COMPILER_SOURCE, "17",
            JavaCore.COMPILER_COMPLIANCE, "17",
            JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17"
    );

    private boolean initialized = false;
    private String currentWorkspaceRoot;

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
        return "Java analysis service running (in-process, JDT Core). Workspace: " + currentWorkspaceRoot
                + ". Java files: " + javaFiles.size() + ".";
    }

    // -------------------------------------------------------------------------
    // Diagnostics (compiler-level)
    // -------------------------------------------------------------------------

    /**
     * Parse a Java source file using the Eclipse compiler and return any
     * problems found (syntax errors, type errors when bindings are available).
     *
     * @param filePath absolute path, or relative to the workspace root
     */
    public String getDiagnostics(String filePath) {
        Path path = resolveFilePath(filePath);
        if (path == null) {
            return "File not found: " + filePath;
        }

        try {
            char[] source = Files.readString(path).toCharArray();
            CompilationUnit cu = parseSource(source, path.getFileName().toString());

            IProblem[] problems = cu.getProblems();
            if (problems.length == 0) {
                return "No issues found in: " + path.getFileName();
            }

            StringBuilder sb = new StringBuilder();
            sb.append(problems.length)
              .append(" issue(s) in ").append(path.getFileName()).append(":\n");
            for (IProblem p : problems) {
                sb.append("  [").append(p.isError() ? "ERROR" : "WARNING")
                  .append("] line ").append(p.getSourceLineNumber())
                  .append(": ").append(p.getMessage()).append("\n");
            }
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
     * types, methods, fields, constructors) using the JDT Core DOM AST.
     *
     * @param filePath absolute path, or relative to the workspace root
     */
    public String getSymbols(String filePath) {
        Path path = resolveFilePath(filePath);
        if (path == null) {
            return "File not found: " + filePath;
        }

        try {
            char[] source = Files.readString(path).toCharArray();
            CompilationUnit cu = parseSource(source, path.getFileName().toString());

            // If there are hard errors, we can still try to extract symbols
            // (JDT Core recovers partial ASTs) but warn the user.
            IProblem[] errors = cu.getProblems();
            boolean hasErrors = false;
            for (IProblem p : errors) {
                if (p.isError()) { hasErrors = true; break; }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Symbols in ").append(path.getFileName()).append(":\n");

            if (hasErrors) {
                sb.append("  (file has errors — symbols may be incomplete)\n");
            }

            PackageDeclaration pkg = cu.getPackage();
            if (pkg != null) {
                sb.append("  Package: ").append(pkg.getName()).append("\n");
            }

            for (Object imp : cu.imports()) {
                ImportDeclaration id = (ImportDeclaration) imp;
                sb.append("  Import: ").append(id.getName())
                  .append(id.isOnDemand() ? ".*" : "").append("\n");
            }

            cu.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    sb.append("  ").append(node.isInterface() ? "Interface" : "Class")
                      .append(": ").append(node.getName()).append("\n");
                    return true;
                }

                @Override
                public boolean visit(EnumDeclaration node) {
                    sb.append("  Enum: ").append(node.getName()).append("\n");
                    return true;
                }

                @Override
                public boolean visit(FieldDeclaration node) {
                    for (Object frag : node.fragments()) {
                        VariableDeclarationFragment v = (VariableDeclarationFragment) frag;
                        sb.append("    Field: ").append(v.getName())
                          .append(" : ").append(node.getType()).append("\n");
                    }
                    return false;
                }

                @Override
                public boolean visit(MethodDeclaration node) {
                    if (node.isConstructor()) {
                        sb.append("    Constructor: ").append(node.getName())
                          .append("(").append(formatParams(node)).append(")\n");
                    } else {
                        sb.append("    Method: ").append(node.getName())
                          .append("(").append(formatParams(node)).append(")")
                          .append(" : ").append(node.getReturnType2()).append("\n");
                    }
                    return false;
                }
            });

            return sb.toString().stripTrailing();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // JDT Core helpers
    // -------------------------------------------------------------------------

    /**
     * Create a JDT Core {@link ASTParser}, parse the given source and return
     * the resulting {@link CompilationUnit}.
     */
    CompilationUnit parseSource(char[] source, String unitName) {
        ASTParser parser = ASTParser.newParser(AST.JLS17);
        parser.setSource(source);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(false);
        parser.setStatementsRecovery(true);
        parser.setBindingsRecovery(true);
        parser.setEnvironment(null, null, null, true);
        parser.setUnitName(unitName);
        parser.setCompilerOptions(COMPILER_OPTIONS);
        return (CompilationUnit) parser.createAST(null);
    }

    /** Format method parameters as a comma-separated string. */
    private static String formatParams(MethodDeclaration method) {
        List<?> params = method.parameters();
        if (params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            SingleVariableDeclaration p = (SingleVariableDeclaration) params.get(i);
            if (i > 0) sb.append(", ");
            sb.append(p.getType()).append(" ").append(p.getName());
        }
        return sb.toString();
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