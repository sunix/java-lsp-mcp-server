# Java LSP MCP Server

A Model Context Protocol (MCP) server that exposes Java Language Server Protocol (Eclipse JDT-LS) functionality to AI assistants like GitHub Copilot. This allows AI assistants to interact with Java codebases through standard LSP features like code completion, diagnostics, symbol definitions, and more.

## 🚧 Work in Progress

This project is currently under active development. Basic infrastructure is complete, with workspace initialization working and ready for full jdtls integration.

## ✅ What's Been Implemented

### Core Infrastructure
- **Quarkus-based MCP Server** - HTTP transport for MCP protocol communication
- **LSP4J Integration** - Library for communicating with Eclipse JDT Language Server
- **Workspace Management** - Smart detection and initialization of Java projects (Maven/Gradle)
- **Test Java Project** - Complete sample project in `test-workspace/` for testing

### Available MCP Tools

| Tool | Description | Status |
|------|-------------|--------|
| `helloworld()` | Basic greeting message | ✅ Working |
| `checkJdtls()` | Check jdtls connection status and workspace info | ✅ Working |
| `initializeWorkspace(path)` | Initialize Java workspace (use "default" for test project) | ✅ Working |
| `getTestWorkspacePath()` | Get path to the default test workspace | ✅ Working |

### Test Workspace
- **Location**: `test-workspace/`
- **Type**: Maven project with Java 17
- **Content**: Calculator class with comprehensive tests
- **Status**: ✅ Compiles and ready for jdtls

## 🎯 Next Steps

1. **JDTLS Process Integration** - Start and manage Eclipse JDT Language Server process
2. **LSP Communication** - Establish two-way communication with jdtls
3. **Core LSP Tools** - Expose key language features:
   - `getSymbols(file)` - Extract symbols from Java files
   - `getCompletions(file, line, column)` - Code completion
   - `getDiagnostics(file)` - Compilation errors and warnings
   - `formatCode(file)` - Code formatting
   - `getDefinition(file, line, column)` - Go to definition
4. **Error Handling** - Robust error handling for LSP communication
5. **Performance Optimization** - Efficient caching and connection management

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

### Test the Available Tools
You can test the MCP tools directly through the connected client or using the dev UI at `http://localhost:8080/q/dev/`.

**Example Tool Usage:**
```bash
# Initialize the default test workspace
initializeWorkspace("default")
# → "Workspace initialized: /path/to/test-workspace (Maven project)"

# Check jdtls status
checkJdtls()
# → "Workspace initialized: /path/to/test-workspace, but JDTLS not connected yet."

# Get test workspace path
getTestWorkspacePath()
# → "/path/to/test-workspace"
```

## 🔗 Connecting with GitHub Copilot in VS Code

### Option 1: Direct HTTP Connection
1. **Start the MCP Server** (see above)
2. **Configure VS Code Settings** - Add to your VS Code `settings.json`:
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

### Verification
Once connected, you should be able to use the Java LSP tools in GitHub Copilot chat:
```
@workspace /initializeWorkspace default
@workspace /checkJdtls
```

## 🏗️ Project Structure

```
java-lsp-mcp-server/
├── pom.xml                           # Main project configuration
├── src/main/java/org/sunix/
│   ├── MyTool.java                   # MCP tool implementations
│   └── JdtlsConnectionService.java   # JDTLS connection management
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
- JDTLS process management and communication
- LSP feature exposure through MCP tools
- Error handling and robustness
- Performance optimization

## 📚 References

- [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) - Protocol for AI-tool communication
- [Eclipse JDT Language Server](https://github.com/eclipse/eclipse.jdt.ls) - Java language server implementation
- [LSP4J](https://github.com/eclipse/lsp4j) - Language Server Protocol implementation for Java
- [Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/) - Quarkus extension for MCP servers

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
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

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
