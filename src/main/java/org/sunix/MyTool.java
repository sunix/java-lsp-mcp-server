package org.sunix;

import io.quarkiverse.mcp.server.Tool;
import jakarta.inject.Inject;

public class MyTool {

    @Inject
    JdtlsConnectionService jdtlsService;

    @Tool(description = "Returns a greeting message")
    public String helloworld() {
        return "Hello, JChateau!";
    }

    @Tool(description = "Check Java analysis service status and workspace info")
    public String checkJdtls() {
        return jdtlsService.getServerInfo();
    }

    @Tool(description = "Initialize a Java workspace for analysis. "
            + "Provide workspace path or use 'default' for test workspace.")
    public String initializeWorkspace(String workspacePath) {
        if ("default".equalsIgnoreCase(workspacePath)) {
            workspacePath = jdtlsService.getDefaultTestWorkspace();
        }
        return jdtlsService.initializeWorkspace(workspacePath);
    }

    @Tool(description = "Get syntax diagnostics (errors/warnings) for a Java source file. "
            + "Accepts an absolute path or a path relative to the workspace root.")
    public String getDiagnostics(String filePath) {
        return jdtlsService.getDiagnostics(filePath);
    }

    @Tool(description = "Extract symbols (classes, methods, fields) from a Java source file. "
            + "Accepts an absolute path or a path relative to the workspace root.")
    public String getSymbols(String filePath) {
        return jdtlsService.getSymbols(filePath);
    }

    @Tool(description = "Get the default test workspace path")
    public String getTestWorkspacePath() {
        return jdtlsService.getDefaultTestWorkspace();
    }
}
