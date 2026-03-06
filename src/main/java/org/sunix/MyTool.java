package org.sunix;

import io.quarkiverse.mcp.server.Tool;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class MyTool {
    
    @Inject
    JdtlsConnectionService jdtlsService;
    
    @Tool(description = "Returns a greeting message")
    public String helloworld() {
        return "Hello, JChateau!";
    }
    
    @Tool(description = "Check jdtls connection status and get basic server info")
    public String checkJdtls() {
        if (!jdtlsService.isConnected()) {
            String workspace = jdtlsService.getCurrentWorkspace();
            if (workspace != null) {
                return "Workspace initialized: " + workspace + ", but JDTLS not connected yet.";
            }
            return "JDTLS is not connected. Connection setup needed.";
        }
        
        try {
            return jdtlsService.getServerInfo().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error checking JDTLS status: " + e.getMessage();
        }
    }
    
    @Tool(description = "Initialize a Java workspace for jdtls. Provide workspace path or use 'default' for test workspace")
    public String initializeWorkspace(String workspacePath) {
        if ("default".equalsIgnoreCase(workspacePath)) {
            workspacePath = jdtlsService.getDefaultTestWorkspace();
        }
        
        try {
            return jdtlsService.initializeWorkspace(workspacePath).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error initializing workspace: " + e.getMessage();
        }
    }
    
    @Tool(description = "Get the default test workspace path")
    public String getTestWorkspacePath() {
        return jdtlsService.getDefaultTestWorkspace();
    }
}
