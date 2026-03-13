# Agent Guide – java-lsp-mcp-server

## Project Goal

This project bridges the gap between AI coding assistants and the Java ecosystem by exposing the Eclipse JDT Language Server (JDTLS) as a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server.

### The Flow

```
Human (prompting) → GitHub Copilot / Claude Code
                 → java-lsp-mcp-server (this project, MCP tools)
                 → Eclipse JDT Language Server (jdt.ls, via LSP protocol)
                 → Java codebase intelligence (completions, diagnostics, symbols, …)
```

This mirrors the way [vscode-java](https://github.com/redhat-developer/vscode-java) exposes jdt.ls to human developers through VS Code, but instead of a human interacting through an IDE, an LLM agent interacts programmatically via MCP tools.

When coding in this project, keep this goal in mind:
- Every MCP tool should make an LLM agent a more effective Java developer.
- Get inspiration from [vscode-java](https://github.com/redhat-developer/vscode-java) for how it sets up and configures JDTLS.
- Refer to the [eclipse.jdt.ls](https://github.com/eclipse-jdtls/eclipse.jdt.ls) project for LSP protocol details and JDTLS capabilities.

---

## Environment Requirements

| Requirement | Version | Notes |
|-------------|---------|-------|
| Java (JDK)  | 25      | Used to compile and run the Quarkus server. Install via [sdkman](https://sdkman.io/). |
| Maven       | 3.9+    | Maven Wrapper (`./mvnw`) is included. |
| Eclipse JDT Language Server (jdtls) | Latest | **Downloaded automatically** on first `startJdtls()` call (requires `tar` and internet). Manual install also supported. |
| `tar`                               | Any    | Required for the auto-download extraction (standard on Linux/macOS). |

### Installing Java 25 with sdkman

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25.0.2-tem
```

### JDTLS Installation

**Option A – Automatic download (recommended, zero config)**

JDTLS is downloaded and installed automatically the first time `startJdtls()` is called and no local installation is found. The latest release is fetched from Eclipse's milestone server and stored in `~/.local/share/java-lsp-mcp-server/jdtls`.

You can also trigger an explicit install using the `installJdtls()` MCP tool:
```
installJdtls()
→ "JDTLS downloaded and installed to: /home/$USER/.local/share/java-lsp-mcp-server/jdtls"
```

To pin a specific JDTLS version:
```properties
# src/main/resources/application.properties
jdtls.download.url=https://download.eclipse.org/jdtls/milestones/1.40.0/jdt-language-server-1.40.0-202503201301.tar.gz
```

To disable automatic download entirely:
```properties
jdtls.auto.download=false
```

**Option B – Python package**:
```bash
pip install jdtls
# The binary is now on PATH; the home directory is: dirname $(dirname $(which jdtls))
```

**Option C – Manual download**:
```bash
mkdir -p ~/.local/share/jdtls
curl -L "https://download.eclipse.org/jdtls/milestones/$(curl -s https://download.eclipse.org/jdtls/milestones/ | grep -oP '[\d.]+(?=/)' | sort -V | tail -1)/jdt-language-server-latest.tar.gz" \
  | tar -xz -C ~/.local/share/jdtls
```

**Option D – Configure a custom path** (if installed elsewhere):
```properties
# src/main/resources/application.properties
jdtls.install.path=/path/to/your/jdtls
```

JDTLS is resolved in this priority order: managed install directory (`~/.local/share/java-lsp-mcp-server/jdtls`) → `jdtls.install.path` config → system `PATH` → common paths (`~/.local/share/jdtls`, `/usr/local/jdtls`, `/opt/jdtls`).

---

## Building the Project

```bash
# Compile only
./mvnw compile

# Package (produces target/quarkus-app/)
./mvnw package

# Run unit tests (no JDTLS required)
./mvnw test
```

---

## Starting the Server in Dev Mode

Quarkus dev mode provides live-reload on code changes:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"   # activate Java 25 if using sdkman
./mvnw quarkus:dev
```

The MCP server starts on `http://localhost:8080`. The MCP endpoint is available at `http://localhost:8080/mcp`.

---

## Self-Testing with the MCP Server and test-workspace

The repository contains a ready-made Java project in `test-workspace/` (a `Calculator` Maven project). Use it to verify that the MCP tools work end-to-end.

### Step-by-step

1. **Start the server** (in a separate terminal):
   ```bash
   ./mvnw quarkus:dev
   ```

2. **Use the MCP tools in order** – call each tool via your MCP client and verify the expected output:

   ```
   installJdtls()           ← optional: explicit install; startJdtls() does this automatically if needed
   → "JDTLS downloaded and installed to: /home/$USER/.local/share/java-lsp-mcp-server/jdtls"

   startJdtls()             ← auto-downloads JDTLS if not already installed
   → "JDTLS started successfully. PID: <pid>. Use initializeWorkspace(<path>) to open a Java project."

   checkJdtls()
   → "JDTLS running. PID: <pid>. Workspace: not set"

   initializeWorkspace("default")
   → "Workspace initialized with JDTLS: <repo-root>/test-workspace (Maven project). LSP handshake complete."

   getSymbols("<repo-root>/test-workspace/src/main/java/com/example/Calculator.java")
   → Lists Class Calculator and its methods (add, subtract, multiply, divide, …).

   getDiagnostics("<repo-root>/test-workspace/src/main/java/com/example/Calculator.java")
   → "No diagnostics for: …/Calculator.java"

   getCompletions("<repo-root>/test-workspace/src/main/java/com/example/Calculator.java", 4, 15)
   → A list of completion items at that position.

   formatCode("<repo-root>/test-workspace/src/main/java/com/example/Calculator.java")
   → "File formatted: … (N edit(s) applied)"  or  "No formatting changes needed for: …"

   getDefinition("<repo-root>/test-workspace/src/main/java/com/example/Calculator.java", 8, 10)
   → "Definition at line …"

   stopJdtls()
   → "JDTLS stopped. PID was: <pid>"
   ```

3. **Run unit tests** to validate non-JDTLS logic (workspace validation, text-edit helpers, etc.):
   ```bash
   ./mvnw test
   ```

---

## Conventional Commits

All commits in this project **must** follow the [Conventional Commits](https://www.conventionalcommits.org/) specification.

### Format

```
<type>(<scope>): <short description>

[optional body]

[optional footer(s)]
```

### Common types

| Type       | When to use |
|------------|-------------|
| `feat`     | A new MCP tool, LSP feature, or user-visible capability |
| `fix`      | A bug fix |
| `refactor` | Code restructuring without behaviour change |
| `test`     | Adding or updating tests |
| `docs`     | Documentation changes only |
| `chore`    | Build scripts, CI config, dependency updates |
| `perf`     | Performance improvements |

### Examples

```
feat(lsp): add textDocument/hover MCP tool
fix(jdtls): handle process restart after unexpected exit
refactor(workspace): extract workspace validation into separate class
test(lsp): add unit tests for getDefinition when JDTLS is not running
docs: update agent.md with JDTLS installation options
chore(deps): bump quarkus-platform to 3.33.0
```

---

## Project Architecture

```
java-lsp-mcp-server/
├── pom.xml                                       # Maven build (maven.compiler.release=17; run with Java 25 JVM)
├── src/main/java/org/sunix/
│   ├── MyTool.java                               # @Tool-annotated MCP tool definitions (thin wrappers)
│   └── JdtlsConnectionService.java              # JDTLS process management + LSP communication
├── src/main/resources/
│   └── application.properties                   # jdtls.install.path and other config
├── src/test/java/org/sunix/
│   └── JdtlsConnectionServiceTest.java          # Unit tests (JUnit 5 + Quarkus test)
└── test-workspace/                              # Sample Maven project used for integration testing
    └── src/main/java/com/example/Calculator.java
```

### Key classes

- **`MyTool`** – Thin MCP layer. Each `@Tool` method validates pre-conditions, delegates to `JdtlsConnectionService`, and formats the result string for the LLM. Exposes: `helloworld`, `installJdtls`, `startJdtls`, `stopJdtls`, `checkJdtls`, `initializeWorkspace`, `getTestWorkspacePath`, `getSymbols`, `getCompletions`, `getDiagnostics`, `formatCode`, `getDefinition`.
- **`JdtlsConnectionService`** – The core engine:
  - Resolves the JDTLS installation path (managed dir → `jdtls.install.path` → PATH → common paths).
  - **Auto-downloads JDTLS** from Eclipse's milestone server on first use (`jdtls.auto.download=true` by default); extracts via `tar`.
  - Manages the JDTLS subprocess lifecycle (`start`, `stop`, liveness check).
  - Maintains the LSP4J launcher and language-server proxy.
  - Performs the LSP initialize/initialized handshake.
  - Implements all LSP requests (`textDocument/documentSymbol`, `textDocument/completion`, `textDocument/formatting`, `textDocument/definition`) and handles async `publishDiagnostics` notifications.

---

## Key References

- [vscode-java](https://github.com/redhat-developer/vscode-java) – Reference implementation for JDTLS setup and configuration patterns.
- [eclipse.jdt.ls](https://github.com/eclipse-jdtls/eclipse.jdt.ls) – The language server this project wraps; consult for supported capabilities and initialization options.
- [LSP Specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/) – Protocol reference for implementing new LSP features.
- [LSP4J](https://github.com/eclipse/lsp4j) – Java library used to communicate with JDTLS over stdin/stdout.
- [Quarkus MCP Server extension](https://docs.quarkiverse.io/quarkus-mcp-server/dev/) – MCP server framework used by this project.
- [Model Context Protocol](https://modelcontextprotocol.io/) – The protocol that connects LLM agents to this server.
