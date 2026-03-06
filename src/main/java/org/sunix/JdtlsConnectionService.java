package org.sunix;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class JdtlsConnectionService {
    
    private LanguageServer languageServer;
    private Process jdtlsProcess;
    private boolean connected = false;
    private String currentWorkspaceRoot;
    
    public boolean isConnected() {
        return connected;
    }
    
    public String getCurrentWorkspace() {
        return currentWorkspaceRoot;
    }
    
    public CompletableFuture<String> initializeWorkspace(String workspacePath) {
        if (connected) {
            return CompletableFuture.completedFuture("Already connected. Current workspace: " + currentWorkspaceRoot);
        }
        
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
            return CompletableFuture.completedFuture("Not a valid Java project. Missing pom.xml or build.gradle in: " + workspacePath);
        }
        
        currentWorkspaceRoot = workspacePath;
        
        return CompletableFuture.completedFuture("Workspace initialized: " + workspacePath + 
            (pomFile.exists() ? " (Maven project)" : " (Gradle project)"));
    }
    
    public CompletableFuture<String> getServerInfo() {
        if (!connected) {
            if (currentWorkspaceRoot != null) {
                return CompletableFuture.completedFuture("Workspace set: " + currentWorkspaceRoot + ", but jdtls not started yet");
            }
            return CompletableFuture.completedFuture("Not connected to jdtls");
        }
        
        // Simple test - get server capabilities
        return languageServer.initialize(new InitializeParams())
            .thenApply(result -> {
                ServerCapabilities capabilities = result.getCapabilities();
                return "Connected to jdtls. Workspace: " + currentWorkspaceRoot + 
                       ". Text document sync: " + capabilities.getTextDocumentSync();
            })
            .exceptionally(throwable -> "Error: " + throwable.getMessage());
    }
    
    public String getDefaultTestWorkspace() {
        // Return the path to our test workspace
        return System.getProperty("user.dir") + "/test-workspace";
    }
    
    // Simple language client implementation for testing
    private static class SimpleLanguageClient implements LanguageClient {
        @Override
        public void telemetryEvent(Object object) {
            // Handle telemetry
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            // Handle diagnostics
            System.out.println("Diagnostics for " + diagnostics.getUri() + ": " + 
                             diagnostics.getDiagnostics().size() + " issues");
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            System.out.println("jdtls message: " + messageParams.getMessage());
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
            System.out.println("jdtls log: " + message.getMessage());
        }
    }
    
    // TODO: Add method to start jdtls process and establish connection
    // This would typically involve:
    // 1. Starting jdtls as a subprocess
    // 2. Creating LSP4J launcher with the process streams
    // 3. Initializing the language server with the workspace
}