package org.sunix;

import io.quarkiverse.mcp.server.Tool;
import jakarta.inject.Inject;
import java.util.concurrent.TimeUnit;

public class MyTool {

    /** Timeout (in seconds) for standard LSP tool requests. */
    private static final int LSP_TOOL_TIMEOUT_SECONDS = 15;

    /** Extended timeout (in seconds) for getDiagnostics, which waits for push notifications. */
    private static final int DIAGNOSTICS_TIMEOUT_SECONDS = 20;

    @Inject
    JdtlsConnectionService jdtlsService;

    @Tool(description = "Returns a greeting message")
    public String helloworld() {
        return "Hello, JChateau!";
    }

    @Tool(description = "Start the Eclipse JDT Language Server (JDTLS) process. "
            + "If JDTLS is not installed, it is downloaded automatically from Eclipse's download server "
            + "(controlled by the 'jdtls.auto.download' configuration property). "
            + "The JDTLS home directory is resolved from the 'jdtls.install.path' "
            + "configuration property, the managed install directory, the system PATH, "
            + "or common installation locations (~/.local/share/jdtls, /usr/local/jdtls, /opt/jdtls).")
    public String startJdtls() {
        try {
            return jdtlsService.startJdtls().get(300, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error starting JDTLS: " + e.getMessage();
        }
    }

    @Tool(description = "Download and install the Eclipse JDT Language Server (JDTLS) automatically. "
            + "JDTLS is installed to ~/.local/share/java-lsp-mcp-server/jdtls. "
            + "This only needs to be done once; startJdtls() also triggers auto-download when needed. "
            + "Set 'jdtls.download.url' in application.properties to pin a specific version.")
    public String installJdtls() {
        try {
            return jdtlsService.downloadAndInstallJdtls().get(300, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error installing JDTLS: " + e.getMessage();
        }
    }

    @Tool(description = "Stop the running Eclipse JDT Language Server (JDTLS) process. "
            + "Sends a graceful LSP shutdown/exit sequence before force-destroying the process.")
    public String stopJdtls() {
        try {
            return jdtlsService.stopJdtls().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error stopping JDTLS: " + e.getMessage();
        }
    }

    @Tool(description = "Check jdtls connection status and get basic server info")
    public String checkJdtls() {
        try {
            return jdtlsService.getServerInfo().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error checking JDTLS status: " + e.getMessage();
        }
    }

    @Tool(description = "Initialize a Java workspace for jdtls. Provide workspace path or use 'default' for test workspace. "
            + "If JDTLS is running, the LSP initialize handshake is performed immediately.")
    public String initializeWorkspace(String workspacePath) {
        if ("default".equalsIgnoreCase(workspacePath)) {
            workspacePath = jdtlsService.getDefaultTestWorkspace();
        }

        try {
            return jdtlsService.initializeWorkspace(workspacePath).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error initializing workspace: " + e.getMessage();
        }
    }

    @Tool(description = "Get the default test workspace path")
    public String getTestWorkspacePath() {
        return jdtlsService.getDefaultTestWorkspace();
    }

    @Tool(description = "Extract document symbols (classes, methods, fields) from a Java source file. "
            + "Requires JDTLS running and workspace initialized. "
            + "Provide the absolute path to the .java file.")
    public String getSymbols(String filePath) {
        try {
            return jdtlsService.getSymbols(filePath).get(LSP_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error getting symbols: " + e.getMessage();
        }
    }

    @Tool(description = "Get code completion suggestions at a position in a Java file. "
            + "Line and column are 0-based. "
            + "Requires JDTLS running and workspace initialized.")
    public String getCompletions(String filePath, int line, int column) {
        try {
            return jdtlsService.getCompletions(filePath, line, column).get(LSP_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error getting completions: " + e.getMessage();
        }
    }

    @Tool(description = "Get compilation errors and warnings (diagnostics) for a Java file. "
            + "Requires JDTLS running and workspace initialized. "
            + "Provide the absolute path to the .java file.")
    public String getDiagnostics(String filePath) {
        try {
            return jdtlsService.getDiagnostics(filePath).get(DIAGNOSTICS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error getting diagnostics: " + e.getMessage();
        }
    }

    @Tool(description = "Format a Java source file using the JDTLS formatter and write the result back to disk. "
            + "Requires JDTLS running and workspace initialized. "
            + "Provide the absolute path to the .java file.")
    public String formatCode(String filePath) {
        try {
            return jdtlsService.formatCode(filePath).get(LSP_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error formatting code: " + e.getMessage();
        }
    }

    @Tool(description = "Go to definition for a symbol at a given position in a Java file. "
            + "Line and column are 0-based. "
            + "Requires JDTLS running and workspace initialized.")
    public String getDefinition(String filePath, int line, int column) {
        try {
            return jdtlsService.getDefinition(filePath, line, column).get(LSP_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error getting definition: " + e.getMessage();
        }
    }
}
