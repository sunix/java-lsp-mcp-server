package org.sunix;

import io.quarkiverse.mcp.server.Tool;
import jakarta.inject.Inject;
import java.util.concurrent.TimeUnit;

public class MyTool {

    @Inject
    JdtlsConnectionService jdtlsService;

    @Tool(description = "Returns a greeting message")
    public String helloworld() {
        return "Hello, JChateau!";
    }

    @Tool(description = "Start the Eclipse JDT Language Server (JDTLS) process. "
            + "The JDTLS home directory is resolved from the 'jdtls.install.path' "
            + "configuration property, the system PATH, or common installation locations "
            + "(~/.local/share/jdtls, /usr/local/jdtls, /opt/jdtls).")
    public String startJdtls() {
        try {
            return jdtlsService.startJdtls().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error starting JDTLS: " + e.getMessage();
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
}
