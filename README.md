# Java LSP MCP Server

A Model Context Protocol (MCP) server that exposes Java Language Server Protocol (Eclipse JDT-LS) functionality to AI assistants like GitHub Copilot. This allows AI assistants to interact with Java codebases through standard LSP features like code completion, diagnostics, symbol definitions, and more.

## 🚧 Work in Progress

This project is currently under active development. Core LSP tools are now implemented on top of the JDTLS process management layer.

## ✅ What's Been Implemented

### Core Infrastructure
- **Quarkus-based MCP Server** – HTTP transport for MCP protocol communication
- **LSP4J Integration** – Library for communicating with Eclipse JDT Language Server
- **Workspace Management** – Smart detection and initialization of Java projects (Maven/Gradle)
- **Test Java Project** – Complete sample project in `test-workspace/` for testing
- **JDTLS Process Management** – Start, connect to, and stop the Eclipse JDT Language Server process

### Available MCP Tools

| Tool | Description | Status |
|------|-------------|--------|
| `helloworld()` | Basic greeting message | ✅ Working |
| `startJdtls()` | Start the Eclipse JDT Language Server process | ✅ Working |
| `stopJdtls()` | Stop the running JDTLS process (graceful + force) | ✅ Working |
| `checkJdtls()` | Check JDTLS process status and PID | ✅ Working |
| `initializeWorkspace(path)` | Initialize Java workspace; if JDTLS is running, performs the LSP handshake | ✅ Working |
| `getTestWorkspacePath()` | Get path to the default test workspace | ✅ Working |
| `getSymbols(filePath)` | Extract symbols (classes, methods, fields) from a Java file | ✅ Working |
| `getCompletions(filePath, line, column)` | Code completion at a 0-based position | ✅ Working |
| `getDiagnostics(filePath)` | Compilation errors and warnings | ✅ Working |
| `formatCode(filePath)` | Format a Java file and write result to disk | ✅ Working |
| `getDefinition(filePath, line, column)` | Go to definition at a 0-based position | ✅ Working |

### JDTLS Process Integration
- **Process Discovery** – Resolves JDTLS from the `jdtls.install.path` config property, the system `PATH`, or common paths (`~/.local/share/jdtls`, `/usr/local/jdtls`, `/opt/jdtls`)
- **Process Lifecycle** – Starts JDTLS as a subprocess with the required JVM flags and OSGi arguments
- **LSP4J Connection** – Connects to the process over its `stdin`/`stdout` streams using an LSP4J Launcher
- **LSP Handshake** – Sends the `initialize` / `initialized` sequence when `initializeWorkspace()` is called with JDTLS running
- **Clean Shutdown** – Sends the LSP `shutdown` + `exit` sequence before force-destroying the process
- **Configurable** – JDTLS path is controlled by `jdtls.install.path` in `application.properties`

### Core LSP Features
- **`getSymbols`** – Opens the file in JDTLS via `textDocument/didOpen` and calls `textDocument/documentSymbol` to return all top-level and nested symbols with their kind and line number.
- **`getCompletions`** – Opens the file, calls `textDocument/completion` at the given 0-based `(line, column)` position, and returns up to 20 completion items with labels and details.
- **`getDiagnostics`** – Opens the file, then waits up to 10 s for JDTLS to push `publishDiagnostics` notifications, and returns all errors/warnings with severity and line number.
- **`formatCode`** – Opens the file, calls `textDocument/formatting` with 4-space indentation, applies the returned `TextEdit`s to the file on disk, and notifies JDTLS of the change via `textDocument/didChange`.
- **`getDefinition`** – Opens the file, calls `textDocument/definition` at the given position, and returns the target URI and line number.

### Test Workspace
- **Location**: `test-workspace/`
- **Type**: Maven project with Java 17
- **Content**: Calculator class with comprehensive tests
- **Status**: ✅ Compiles and ready for jdtls

## 📋 Definition of Done – JDTLS Process Integration

The following criteria define when the JDTLS Process Integration is complete and working:

1. **`startJdtls()` works** – Calling the tool starts a JDTLS subprocess, connects via LSP4J, and reports the PID.
2. **`checkJdtls()` reports running** – After `startJdtls()`, the tool reports the PID and current workspace.
3. **`initializeWorkspace(path)` performs the LSP handshake** – When JDTLS is running, the tool sends `initialize`/`initialized` and reports server capabilities.
4. **`stopJdtls()` works** – Calling the tool gracefully shuts down JDTLS and reports the former PID.
5. **Error handling** – When JDTLS is not installed, `startJdtls()` returns a helpful message with installation instructions.

## 🧪 How to Test JDTLS Integration

### Prerequisites
- Java 25 (project requires Java 25)
- Maven 3.9+
- Eclipse JDT Language Server installed (see [Installation](#jdtls-installation))

### JDTLS Installation

**Option A – via Python `jdtls` package** (recommended for quick setup):
```bash
pip install jdtls
# jdtls is now on your PATH and the home directory can be found with: dirname $(which jdtls)/../..
```

**Option B – manual download**:
```bash
mkdir -p ~/.local/share/jdtls
curl -L "https://download.eclipse.org/jdtls/milestones/$(curl -s https://download.eclipse.org/jdtls/milestones/ | grep -oP '[\d.]+(?=/)' | sort -V | tail -1)/jdt-language-server-latest.tar.gz" \
  | tar -xz -C ~/.local/share/jdtls
```

**Option C – configure the path explicitly** (if installed elsewhere):
```properties
# src/main/resources/application.properties
jdtls.install.path=/path/to/your/jdtls
```

### Run the MCP Server
```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"   # if using sdkman for Java 25
mvn quarkus:dev
```

### Test via MCP Tools (step by step)
```
1. startJdtls()
   → "JDTLS started successfully. PID: 12345. Use initializeWorkspace(<path>) to open a Java project."

2. checkJdtls()
   → "JDTLS running. PID: 12345. Workspace: not set"

3. initializeWorkspace("default")
   → "Workspace initialized with JDTLS: /path/to/test-workspace (Maven project). LSP handshake complete."

4. checkJdtls()
   → "JDTLS running. PID: 12345. Workspace: /path/to/test-workspace"

5. stopJdtls()
   → "JDTLS stopped. PID was: 12345"
```

### Run Unit Tests
```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
mvn test
```

The test suite (`JdtlsConnectionServiceTest`) covers:
- Initial disconnected state
- Workspace validation (invalid path, non-Java project, Maven, Gradle)
- Server info messages when JDTLS is not running
- Graceful `stopJdtls()` when not running
- Graceful `startJdtls()` failure when JDTLS is not installed
- JDTLS command-builder and launcher JAR discovery helpers
- Each new LSP tool (`getSymbols`, `getCompletions`, `getDiagnostics`, `formatCode`, `getDefinition`) returns the correct "not running" message when JDTLS is not started
- `applyTextEdits` helper correctly applies single and multiple LSP text edits

## 🧪 End-to-End Test Walkthrough – Core LSP Tools

> **Prerequisites**: JDTLS installed (see above), MCP server running (`mvn quarkus:dev`).

The test workspace contains a `Calculator.java` class. The file paths below use the project root as `$PROJECT`.

### 1. Start JDTLS and open the workspace
```
startJdtls()
→ "JDTLS started successfully. PID: 12345. ..."

initializeWorkspace("default")
→ "Workspace initialized with JDTLS: $PROJECT/test-workspace (Maven project). LSP handshake complete. ..."
```

### 2. getSymbols – extract document symbols
```
getSymbols("$PROJECT/test-workspace/src/main/java/com/example/Calculator.java")
→ Symbols in .../Calculator.java:
    Class Calculator [line 3]
    Method add [line 5]
    Method subtract [line 9]
    Method multiply [line 13]
    Method divide [line 17]
```

### 3. getDiagnostics – compilation errors and warnings
```
getDiagnostics("$PROJECT/test-workspace/src/main/java/com/example/Calculator.java")
→ No diagnostics for: .../Calculator.java
```
*(A file with a deliberate syntax error would report `[Error] line N: <message>`.)*

### 4. getCompletions – code completion
```
getCompletions("$PROJECT/test-workspace/src/main/java/com/example/Calculator.java", 4, 15)
→ Completions at line 5, col 16 in .../Calculator.java:
    add(int a, int b) – int
    ... (other suggestions)
```
*(Line/column are 0-based; adjust to match the position of interest in the file.)*

### 5. formatCode – format the file
```
formatCode("$PROJECT/test-workspace/src/main/java/com/example/Calculator.java")
→ File formatted: .../Calculator.java (N edit(s) applied)
  -- or --
→ No formatting changes needed for: .../Calculator.java
```

### 6. getDefinition – go to definition
```
getDefinition("$PROJECT/test-workspace/src/main/java/com/example/Calculator.java", 8, 10)
→ Definition at line 9, col 11 in .../Calculator.java:
    file:///...Calculator.java line 9
```

### 7. Stop JDTLS
```
stopJdtls()
→ "JDTLS stopped. PID was: 12345"
```

## 🎯 Next Steps

1. **Error Handling** – Robust error handling for LSP communication (timeouts, reconnection)
2. **Performance Optimization** – Efficient caching and connection management; avoid redundant `didOpen` calls across tool invocations
3. **Extended LSP Features** – Hover (`textDocument/hover`), find references (`textDocument/references`), rename (`textDocument/rename`)
4. **Multi-file Workspace** – Support multiple open files and cross-file navigation

## 🚀 Running the application in dev mode

### Prerequisites
- Java 25
- Maven 3.9+

### Start the MCP Server
```bash
# Install Java 25 via sdkman (if needed)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25.0.2-tem

# Navigate to project directory
cd java-lsp-mcp-server

# Start in development mode (enables live reloading)
mvn quarkus:dev
```

The server will start on `http://localhost:8080` with MCP endpoints available.

## 🔗 Connecting with GitHub Copilot in VS Code

### Option 1: Direct HTTP Connection
1. **Start the MCP Server** (see above)
2. **Configure VS Code Settings** – Add to your VS Code `settings.json`:
   ```json
   {
     "github.copilot.chat.mcp.servers": {
       "java-lsp-server": {
         "command": "curl",
         "args": ["-X", "POST", "http://localhost:8080/mcp", "-H", "Content-Type: application/json"],
         "transport": "http"
       }
     }
   }
   ```

### Option 2: MCP Client Integration
1. **Install MCP Client** (if available)
2. **Configure Connection** to `http://localhost:8080/mcp`
3. **Connect from GitHub Copilot Chat** using the configured MCP client

## 🏗️ Project Structure

```
java-lsp-mcp-server/
├── pom.xml                           # Main project configuration (Java 17)
├── src/main/java/org/sunix/
│   ├── MyTool.java                   # MCP tool implementations (all @Tool-annotated methods)
│   └── JdtlsConnectionService.java   # JDTLS process management, LSP connection & core LSP tools
├── src/main/resources/
│   └── application.properties        # Configuration (jdtls.install.path, etc.)
├── src/test/java/org/sunix/
│   └── JdtlsConnectionServiceTest.java  # Unit tests for the connection service
├── test-workspace/                   # Test Java project
│   ├── pom.xml                       # Maven configuration
│   └── src/main/java/com/example/
│       └── Calculator.java           # Sample Java class
└── README.md                         # This file
```

## 🧪 Testing

### Test the Sample Java Project
```bash
# Navigate to test workspace
cd test-workspace

# Compile the test project
mvn compile

# Run tests
mvn test
```

### Test MCP Server Tools
With the server running, you can test the available MCP tools through any connected client.

## 🤝 Contributing

This is an active WIP project. Current focus areas:
- LSP feature exposure through MCP tools (diagnostics, completions, symbols…)
- Error handling and robustness
- Performance optimization

## 📚 References

- [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) – Protocol for AI-tool communication
- [Eclipse JDT Language Server](https://github.com/eclipse/eclipse.jdt.ls) – Java language server implementation
- [LSP4J](https://github.com/eclipse/lsp4j) – Language Server Protocol implementation for Java
- [Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/) – Quarkus extension for MCP servers

---

## Legacy Quarkus Information

This project uses Quarkus, the Supersonic Subatomic Java Framework.

### Additional Quarkus Commands

**Build and run commands:**
```shell script
./mvnw package
./mvnw package -Dnative
```

**Learn more:** <https://quarkus.io/>

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it's not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/java-lsp-mcp-server-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- MCP Server - HTTP ([guide](https://docs.quarkiverse.io/quarkus-mcp-server/dev/)): The HTTP/SSE transport the MCP server.
