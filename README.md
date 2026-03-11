# Java LSP MCP Server

A Model Context Protocol (MCP) server that exposes Java language analysis functionality to AI assistants like GitHub Copilot. This allows AI assistants to interact with Java codebases through features like diagnostics, symbol extraction, and more — all running in-process, no external JDTLS required.

## 🚧 Work in Progress

This project is currently under active development. In-process Java analysis is implemented using Eclipse JDT Core APIs and ready for use.

## ✅ What's Been Implemented

### Core Infrastructure
- **Quarkus-based MCP Server** – HTTP transport for MCP protocol communication
- **In-process Java Analysis** – Eclipse JDT Core-based parsing, diagnostics and symbol extraction running inside the same JVM (no external process needed)
- **Workspace Management** – Smart detection and initialization of Java projects (Maven/Gradle)
- **Test Java Project** – Complete sample project in `test-workspace/` for testing

### Available MCP Tools

| Tool | Description | Status |
|------|-------------|--------|
| `helloworld()` | Basic greeting message | ✅ Working |
| `checkJdtls()` | Check analysis service status and workspace info | ✅ Working |
| `initializeWorkspace(path)` | Initialize Java workspace (use "default" for test project) | ✅ Working |
| `getDiagnostics(file)` | Get syntax diagnostics (errors/warnings) for a Java file | ✅ Working |
| `getSymbols(file)` | Extract symbols (classes, methods, fields) from a Java file | ✅ Working |
| `getTestWorkspacePath()` | Get path to the default test workspace | ✅ Working |

### In-process Java Analysis (Hybrid Approach)
Instead of spawning a separate JDTLS process, all Java analysis runs **in the same JVM** using [Eclipse JDT Core](https://www.eclipse.org/jdt/core/) — the same compiler and AST APIs that power Eclipse IDE and JDTLS:
- **No external installation** – no JDTLS download or configuration needed
- **Always available** – no start/stop lifecycle; the analysis service is ready immediately
- **Compiler-level diagnostics** – the Eclipse Java compiler reports syntax errors, type errors, and warnings with line numbers
- **Symbol extraction** – classes, interfaces, enums, methods, fields, constructors via the JDT DOM AST
- **Incremental design** – more features (type resolution with classpath, code completion, refactoring) can be added over time using the same JDT Core APIs

### Test Workspace
- **Location**: `test-workspace/`
- **Type**: Maven project with Java 17
- **Content**: Calculator class with comprehensive tests
- **Status**: ✅ Compiles and ready for analysis

## 🧪 How to Test

### Prerequisites
- Java 17 or later
- Maven 3.9+

### Run Unit Tests
```bash
mvn test
```

The test suite (`JdtlsConnectionServiceTest`) covers:
- Workspace validation (invalid path, non-Java project, Maven, Gradle)
- Diagnostics for valid and broken Java files
- Symbol extraction (classes, methods, fields, constructors)
- File path resolution (absolute and workspace-relative)
- Java file listing in workspace

### Run the MCP Server
```bash
mvn quarkus:dev
```

### Test via MCP Tools (step by step)
```
1. initializeWorkspace("default")
   → "Workspace initialized: /path/to/test-workspace (Maven project). Found 1 Java source file(s)."

2. checkJdtls()
   → "Java analysis service running (in-process, JDT Core). Workspace: /path/to/test-workspace. Java files: 1."

3. getDiagnostics("src/main/java/com/example/Calculator.java")
   → "No issues found in: Calculator.java"

4. getSymbols("src/main/java/com/example/Calculator.java")
   → "Symbols in Calculator.java:
        Package: com.example
        Class: Calculator
          Field: history : List<Double>
          Constructor: Calculator()
          Method: add(double a, double b) : double
          ..."
```

## 🎯 Next Steps

1. **Type Resolution** – Add classpath-aware analysis for richer diagnostics
2. **Code Completion** – Suggest completions at a given cursor position
3. **Go to Definition** – Resolve symbol locations across files
4. **Code Formatting** – Format Java source files
5. **Refactoring** – Rename, extract method, etc.

## 🚀 Running the application in dev mode

### Prerequisites
- Java 17 or later
- Maven 3.9+

### Start the MCP Server
```bash
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
│   ├── MyTool.java                   # MCP tool implementations
│   └── JdtlsConnectionService.java   # In-process Java analysis (JDT Core APIs)
├── src/main/resources/
│   └── application.properties        # Configuration
├── src/test/java/org/sunix/
│   └── JdtlsConnectionServiceTest.java  # Unit tests
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
- Richer Java analysis features (type resolution, completions)
- Error handling and robustness
- Performance optimization

## 📚 References

- [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) – Protocol for AI-tool communication
- [Eclipse JDT Core](https://www.eclipse.org/jdt/core/) – Java compiler and AST APIs (same as Eclipse IDE)
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
