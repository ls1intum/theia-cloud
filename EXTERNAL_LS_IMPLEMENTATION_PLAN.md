# External Language Server Implementation Plan

> **Created**: 2026-01-30
> **Status**: ✅ Implementation Complete - Phases 1-6 Done, Phase 7 Requires K8s Cluster
> **Branch**: `feature/external-ls-v2`
> **Last Updated**: 2026-01-30
> **Build Status**: ✅ Java operator compiles | ✅ Extension compiles

## Executive Summary

This document outlines the complete implementation plan for **External Language Server Support** in Theia Cloud. The implementation uses a clean, extensible architecture that integrates with the newly refactored main branch components (PrewarmedResourcePool, IngressManager, K8sResourceFactory, SentryHelper).

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Port Strategy** | Single standard port 5000 | Eliminates per-language port logic; simpler templates |
| **Abstraction** | Configuration-driven Registry | Easy to add new languages without code changes |
| **LS Lifecycle** | Created with session (Eager & Lazy) | LS follows session lifecycle; K8s GC handles cleanup |
| **Pool Strategy** | LS created when session created | For Eager: LS spun up during pre-warming; For Lazy: LS created on-demand |
| **Env Vars** | Keep legacy + add generic | Backward compatible; future-proof |
| **Volume Sharing** | RWX PVC | Both Theia and LS access same workspace |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Theia Cloud Operator                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────┐    ┌──────────────────────────────────────────┐   │
│  │  Session Handlers    │    │          Language Server Module          │   │
│  │                      │    │                                          │   │
│  │  ┌────────────────┐  │    │  ┌────────────────────────────────────┐  │   │
│  │  │ LazySession    │──────>│  │ LanguageServerManager              │  │   │
│  │  │ Handler        │  │    │  │                                    │  │   │
│  │  └────────────────┘  │    │  │ • createLanguageServer(session)    │  │   │
│  │                      │    │  │ • deleteLanguageServer(session)    │  │   │
│  │  ┌────────────────┐  │    │  │ • injectEnvVars(deployment)        │  │   │
│  │  │ EagerSession   │──────>│  └─────────────┬──────────────────────┘  │   │
│  │  │ Handler        │  │    │                │                          │   │
│  │  └────────────────┘  │    │                ▼                          │   │
│  │                      │    │  ┌────────────────────────────────────┐  │   │
│  │  ┌────────────────┐  │    │  │ LanguageServerRegistry             │  │   │
│  │  │ EagerWithLazy  │──────>│  │                                    │  │   │
│  │  │ Fallback       │  │    │  │ Configurations:                    │  │   │
│  │  └────────────────┘  │    │  │ • java: {port: 5000, env: LS_JAVA} │  │   │
│  └──────────────────────┘    │  │ • rust: {port: 5000, env: LS_RUST} │  │   │
│                              │  └─────────────┬──────────────────────┘  │   │
│                              │                │                          │   │
│                              │                ▼                          │   │
│                              │  ┌────────────────────────────────────┐  │   │
│                              │  │ LanguageServerResourceFactory      │  │   │
│                              │  │                                    │  │   │
│                              │  │ • createDeployment(session, cfg)   │  │   │
│                              │  │ • createService(session, cfg)      │  │   │
│                              │  └────────────────────────────────────┘  │   │
│                              └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Kubernetes Resource Layout

```
Session: ws-user123
├── Deployment: ws-user123 (Theia IDE)
│   └── Container: theia
│       ├── ENV: LS_JAVA_HOST=ws-user123-ls
│       ├── ENV: LS_JAVA_PORT=5000
│       ├── ENV: LS_HOST=ws-user123-ls (generic)
│       ├── ENV: LS_PORT=5000 (generic)
│       └── VolumeMount: /home/project → PVC
│
├── Deployment: ws-user123-ls (Language Server)
│   └── Container: language-server
│       ├── Image: ghcr.io/ls1intum/theia/langserver-java:latest
│       ├── Port: 5000
│       └── VolumeMount: /home/project → PVC (same PVC!)
│
├── Service: ws-user123 (Theia)
├── Service: ws-user123-ls (Language Server)
│
└── PVC: ws-user123-data (ReadWriteMany)
```

---

## Session Handler Integration

### Handler Flow Comparison

| Handler | When LS is Created | Integration Point |
|---------|-------------------|-------------------|
| **LazySessionHandler** | When session is created on-demand | After Theia deployment creation |
| **EagerSessionHandler** | When session is reserved from pool | After `pool.completeSessionSetup()` |
| **EagerWithLazyFallbackSessionHandler** | Delegates to Eager or Lazy | No direct integration needed |

### LazySessionHandler Flow

```
User Request → Session Created
    ↓
1. Create Theia Service
2. Create Theia Deployment
   └── Inject LS env vars during creation
3. Create LS Service (if configured)
4. Create LS Deployment (if configured)
5. Update Ingress
6. Session Ready
```

### EagerSessionHandler Flow

```
AppDefinition Added → Pool Pre-warms Instances
    ↓
Session Request → Reserve Instance from Pool
    ↓
1. pool.reserveInstance() → PoolInstance
2. pool.completeSessionSetup() → Labels, ownership
3. Create LS Service (if configured)
4. Create LS Deployment (if configured)
5. Edit Theia Deployment → Inject LS env vars
6. Add Ingress Rule
7. Session Ready
```

### EagerWithLazyFallbackSessionHandler

This handler **delegates** to EagerSessionHandler first, then falls back to LazySessionHandler if no pool capacity. Since both handlers implement LS support, no additional integration is needed in the fallback handler.

---

## Component Specifications

### 1. LanguageServerConfig

**File**: `org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/languageserver/LanguageServerConfig.java`

```java
public record LanguageServerConfig(
    String languageKey,      // "java", "rust", "python"
    String image,            // Docker image
    int containerPort,       // Always 5000
    String hostEnvVar,       // "LS_JAVA_HOST"
    String portEnvVar,       // "LS_JAVA_PORT"
    String cpuLimit,
    String memoryLimit,
    String cpuRequest,
    String memoryRequest
) {
    public static final int STANDARD_LS_PORT = 5000;
}
```

### 2. LanguageServerRegistry

**File**: `org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/languageserver/LanguageServerRegistry.java`

Built-in configurations:

| Language | Image | Host Env | Port Env |
|----------|-------|----------|----------|
| java | ghcr.io/ls1intum/theia/langserver-java:latest | LS_JAVA_HOST | LS_JAVA_PORT |
| rust | ghcr.io/ls1intum/theia/langserver-rust:latest | LS_RUST_HOST | LS_RUST_PORT |

### 3. LanguageServerResourceFactory

**File**: `org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/languageserver/LanguageServerResourceFactory.java`

Methods:
- `createDeployment(Session, LanguageServerConfig, Optional<String> pvcName, String correlationId)`
- `createService(Session, LanguageServerConfig, String correlationId)`
- `injectEnvVarsIntoTheia(Deployment, Session, LanguageServerConfig, String correlationId)`
- `getDeploymentName(Session)` → `{sessionName}-ls`
- `getServiceName(Session)` → `{sessionName}-ls`

### 4. LanguageServerManager

**File**: `org.eclipse.theia.cloud.operator/src/main/java/org/eclipse/theia/cloud/operator/languageserver/LanguageServerManager.java`

High-level API:
- `requiresLanguageServer(AppDefinition)` → boolean
- `getLanguageServerConfig(AppDefinition)` → Optional<LanguageServerConfig>
- `createLanguageServer(Session, AppDefinition, Optional<String> pvcName, String correlationId)` → boolean
- `injectEnvVars(Deployment, Session, AppDefinition, String correlationId)`
- `deleteLanguageServer(Session, String correlationId)`

---

## Template Files

### templateLanguageServerDeployment.yaml

**File**: `org.eclipse.theia.cloud.operator/src/main/resources/templateLanguageServerDeployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: PLACEHOLDER_NAME
  namespace: PLACEHOLDER_NAMESPACE
  labels:
    theia-cloud.io/component: language-server
    theia-cloud.io/session: PLACEHOLDER_SESSION
spec:
  replicas: 1
  selector:
    matchLabels:
      app: PLACEHOLDER_NAME
  template:
    metadata:
      labels:
        app: PLACEHOLDER_NAME
        theia-cloud.io/component: language-server
    spec:
      containers:
        - name: language-server
          image: PLACEHOLDER_IMAGE
          imagePullPolicy: Always
          ports:
            - containerPort: PLACEHOLDER_PORT
              name: lsp
              protocol: TCP
          resources:
            limits:
              cpu: "PLACEHOLDER_CPU_LIMIT"
              memory: "PLACEHOLDER_MEMORY_LIMIT"
            requests:
              cpu: "PLACEHOLDER_CPU_REQUEST"
              memory: "PLACEHOLDER_MEMORY_REQUEST"
          volumeMounts: []
      volumes: []
```

### templateLanguageServerService.yaml

**File**: `org.eclipse.theia.cloud.operator/src/main/resources/templateLanguageServerService.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: PLACEHOLDER_NAME
  namespace: PLACEHOLDER_NAMESPACE
  labels:
    theia-cloud.io/component: language-server
    theia-cloud.io/session: PLACEHOLDER_SESSION
spec:
  type: ClusterIP
  ports:
    - name: lsp
      port: PLACEHOLDER_PORT
      targetPort: PLACEHOLDER_PORT
      protocol: TCP
  selector:
    app: PLACEHOLDER_NAME
```

---

## AppDefinition Configuration

Example AppDefinition with Language Server:

```yaml
apiVersion: theia.cloud/v1beta9
kind: AppDefinition
metadata:
  name: java-ide
spec:
  name: java-ide
  image: ghcr.io/ls1intum/theia/theia-no-ls:latest
  port: 3000
  minInstances: 2  # For eager start
  options:
    # Language Server Configuration
    langserver-image: "ghcr.io/ls1intum/theia/langserver-java:latest"
    langserver-language: "java"  # Optional, auto-detected from image
    
    # Optional resource overrides
    langserver-cpu-limit: "1000m"
    langserver-memory-limit: "2Gi"
    langserver-cpu-request: "200m"
    langserver-memory-request: "512Mi"
```

---

## Theia LSP Extension Updates

**Repository**: https://github.com/ls1intum/theia-lsp-extension

### Changes Required

1. **Update default ports to 5000**
2. **Add generic LS_HOST/LS_PORT fallback**
3. **Improve error handling and logging**

### Updated extension.ts

```typescript
import { workspace, ExtensionContext, TextDocument } from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions } from 'vscode-languageclient/node';
import * as net from 'net';

const clients: Map<string, LanguageClient> = new Map();

interface LanguageConfig {
  hostEnv: string;
  portEnv: string;
  defaultHost: string;
  defaultPort: number;
  fileExtensions: string[];
}

// Updated configuration with standard port 5000
const languageServerConfigs: Record<string, LanguageConfig> = {
  'rust': {
    hostEnv: 'LS_RUST_HOST',
    portEnv: 'LS_RUST_PORT',
    defaultHost: 'rust-language-server',
    defaultPort: 5000,  // CHANGED from 5555
    fileExtensions: ['rs']
  },
  'java': {
    hostEnv: 'LS_JAVA_HOST',
    portEnv: 'LS_JAVA_PORT',
    defaultHost: 'java-language-server',
    defaultPort: 5000,  // CHANGED from 5556
    fileExtensions: ['java']
  }
};

export function activate(context: ExtensionContext) {
  console.log('[LSP-EXT] External Language Server Connector activated');

  function ensureLanguageClient(document: TextDocument): void {
    const langId = document.languageId;
    
    if (!languageServerConfigs[langId] || clients.has(langId)) {
      return;
    }

    const config = languageServerConfigs[langId];
    
    // Try generic vars first, then language-specific, then defaults
    const host = process.env['LS_HOST'] || 
                 process.env[config.hostEnv] || 
                 config.defaultHost;
    const port = parseInt(
      process.env['LS_PORT'] || 
      process.env[config.portEnv] || 
      String(config.defaultPort)
    );

    console.log(`[LSP-EXT] Connecting to ${langId} LS at ${host}:${port}`);

    const serverOptions: ServerOptions = () => {
      return new Promise((resolve, reject) => {
        const socket = net.connect({ host, port });
        
        socket.on('connect', () => {
          console.log(`[LSP-EXT] Connected to ${langId} LS`);
          resolve({ reader: socket, writer: socket });
        });
        
        socket.on('error', (err) => {
          console.error(`[LSP-EXT] Connection error for ${langId}: ${err.message}`);
          reject(err);
        });
      });
    };

    const clientOptions: LanguageClientOptions = {
      documentSelector: [{ scheme: 'file', language: langId }],
      synchronize: {
        fileEvents: workspace.createFileSystemWatcher(
          `**/*.{${config.fileExtensions.join(',')}}`
        ),
      },
    };

    const client = new LanguageClient(
      `${langId}LspConnector`,
      `${langId.toUpperCase()} Language Server (External)`,
      serverOptions,
      clientOptions
    );

    client.start();
    clients.set(langId, client);
  }

  context.subscriptions.push(workspace.onDidOpenTextDocument(ensureLanguageClient));
  workspace.textDocuments.forEach(ensureLanguageClient);
}

export function deactivate(): Thenable<void> {
  const promises: Thenable<void>[] = [];
  for (const client of clients.values()) {
    promises.push(client.stop());
  }
  return Promise.all(promises).then(() => undefined);
}
```

---

## Blueprint Image Updates

**Repository**: https://github.com/ls1intum/artemis-theia-blueprints

### langserver-java/Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk-jammy

ARG JDTLS_VERSION=1.50.0
ARG JDTLS_URL=https://www.eclipse.org/downloads/download.php?file=/jdtls/milestones/${JDTLS_VERSION}/jdt-language-server-${JDTLS_VERSION}-202509041425.tar.gz

# Standard LS port - configurable via env
ENV LS_PORT=5000

RUN apt-get update && apt-get install -y --no-install-recommends \
    wget tar socat && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jdt-ls

RUN wget -q "${JDTLS_URL}" -O jdt-ls.tar.gz && \
    tar -xzf jdt-ls.tar.gz && \
    rm jdt-ls.tar.gz && \
    rm -rf config_win config_mac config_ss_win config_ss_mac config_ss_linux bin *.md LICENSE

RUN mkdir -p /opt/workspace

RUN addgroup --system app && adduser --system --group app
RUN chown -R app:app /opt/jdt-ls && chown -R app:app /opt/workspace

COPY images/languageserver/java/start-ls.sh /opt/start-ls.sh
RUN chmod +x /opt/start-ls.sh

USER app

EXPOSE 5000

ENTRYPOINT ["/opt/start-ls.sh"]
```

### langserver-java/start-ls.sh

```bash
#!/bin/bash
set -e

# Use standard port, configurable via env
SERVER_PORT=${LS_PORT:-5000}

LAUNCHER_JAR=$(find /opt/jdt-ls/plugins -name "org.eclipse.equinox.launcher_*.jar" | head -n 1)

if [ -z "$LAUNCHER_JAR" ] || [ ! -f "$LAUNCHER_JAR" ]; then
  echo "[LS-JAVA] ERROR: Could not find Eclipse Equinox launcher JAR" >&2
  exit 1
fi

cat <<EOF > /tmp/run-jdt.sh
#!/bin/bash
exec java \
  -Declipse.application=org.eclipse.jdt.ls.core.id1 \
  -Dosgi.bundles.defaultStartLevel=4 \
  -Declipse.product=org.eclipse.jdt.ls.core.product \
  -Dlog.level=ALL \
  -Xmx1G \
  --add-modules=ALL-SYSTEM \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  -jar "${LAUNCHER_JAR}" \
  -configuration /opt/jdt-ls/config_linux \
  -data /opt/workspace
EOF

chmod +x /tmp/run-jdt.sh

echo "[LS-JAVA] Starting Java Language Server on port ${SERVER_PORT}"
exec socat TCP-LISTEN:${SERVER_PORT},reuseaddr,fork EXEC:/tmp/run-jdt.sh
```

### langserver-rust/Dockerfile

```dockerfile
FROM alpine:3.22

# Standard LS port - configurable via env
ENV LS_PORT=5000

RUN apk add --no-cache socat rust-analyzer

RUN addgroup -S app && adduser -S app -G app
RUN mkdir -p /home/project && chown -R app:app /home/project

USER app
WORKDIR /home/project

EXPOSE 5000

CMD ["sh", "-c", "echo '[LS-RUST] Starting Rust Language Server on port ${LS_PORT:-5000}' && socat TCP-LISTEN:${LS_PORT:-5000},reuseaddr,fork EXEC:rust-analyzer"]
```

---

## Sentry Instrumentation

All LS operations are instrumented with Sentry spans:

| Operation | Span Name | Tags |
|-----------|-----------|------|
| Create LS | `ls.create` | language, session |
| Create LS Deployment | `ls.deployment.create` | language |
| Create LS Service | `ls.service.create` | language |
| Delete LS | `ls.delete` | session |
| Inject Env Vars | `ls.inject_env` | language |

Example:
```java
ISpan span = SentryHelper.startSpan("ls.create", "Create language server");
span.setTag("language", config.languageKey());
span.setData("session", session.getMetadata().getName());
// ... operation ...
SentryHelper.finishSuccess(span);
```

---

## File Structure

```
theia-cloud/java/operator/org.eclipse.theia.cloud.operator/
├── src/main/java/org/eclipse/theia/cloud/operator/
│   ├── languageserver/                          # NEW PACKAGE
│   │   ├── LanguageServerConfig.java            # Data model
│   │   ├── LanguageServerRegistry.java          # Language configurations
│   │   ├── LanguageServerResourceFactory.java   # K8s resource creation
│   │   └── LanguageServerManager.java           # High-level orchestration
│   ├── handler/session/
│   │   ├── LazySessionHandler.java              # MODIFIED
│   │   └── EagerSessionHandler.java             # MODIFIED
│   └── di/
│       └── AbstractTheiaCloudOperatorModule.java # MODIFIED (DI bindings)
│
├── src/main/resources/
│   ├── templateLanguageServerDeployment.yaml   # NEW
│   └── templateLanguageServerService.yaml      # NEW

artemis-theia-blueprints/
├── images/languageserver/
│   ├── java/
│   │   ├── Dockerfile                          # MODIFIED (port 5000)
│   │   └── start-ls.sh                         # MODIFIED (port 5000)
│   └── rust/
│       └── Dockerfile                          # MODIFIED (port 5000)

theia-lsp-extension/
├── src/
│   └── extension.ts                            # MODIFIED (port 5000, generic env)
└── package.json                                # Version bump
```

---

## Implementation Phases

### Phase 1: Core Infrastructure ✅ COMPLETE
- [x] Create feature branch from origin/main
- [x] Create `languageserver/` package
- [x] Implement `LanguageServerConfig` record
- [x] Implement `LanguageServerRegistry` with Java/Rust
- [x] Create `templateLanguageServerDeployment.yaml`
- [x] Create `templateLanguageServerService.yaml`

### Phase 2: Resource Factory ✅ COMPLETE
- [x] Implement `LanguageServerResourceFactory`
- [x] Add deployment creation with Sentry tracing
- [x] Add service creation with Sentry tracing
- [x] Add PVC volume mounting logic
- [x] Add OwnerReference handling
- [x] Add env var injection method

### Phase 3: Manager & Session Handler Integration ✅ COMPLETE
- [x] Implement `LanguageServerManager`
- [x] Add DI bindings in AbstractTheiaCloudOperatorModule
- [x] Integrate with `LazySessionHandler.doSessionAdded()`
- [x] Integrate with `LazySessionHandler.doSessionDeleted()`
- [x] Integrate with `EagerSessionHandler.trySessionAdded()`
- [x] Integrate with `EagerSessionHandler.sessionDeleted()`
- [x] Verify EagerWithLazyFallback works (no changes needed)

### Phase 4: Blueprint Docker Images ✅ COMPLETE
- [x] Update langserver-java Dockerfile (port 5000)
- [x] Update langserver-java start-ls.sh (port 5000)
- [x] Update langserver-rust Dockerfile (port 5000)
- [x] Build verification passed

### Phase 5: Theia LSP Extension ✅ COMPLETE
- [x] Update extension.ts with port 5000
- [x] Add generic LS_HOST/LS_PORT support
- [x] Bump version in package.json (0.0.5 → 0.0.6)
- [x] Build verification passed

### Phase 6: Cleanup & Documentation ✅ COMPLETE
- [x] Old `LangServerUtil.java` - N/A (doesn't exist)
- [x] Old `templateLSDeployment.yaml` - N/A (doesn't exist)
- [x] Old `templateLSService.yaml` - N/A (doesn't exist)
- [x] Old constants from `AddedHandlerUtil.java` - N/A (don't exist)
- [ ] Update README with LS configuration docs (optional)

### Phase 7: Testing ⏳ BLOCKED (Requires K8s Cluster)
- [ ] Test Lazy path with Java LS
- [ ] Test Lazy path with Rust LS  
- [ ] Test Eager path with Java LS
- [ ] Test Eager path with Rust LS
- [ ] Test Fallback path (Eager → Lazy with LS)
- [ ] Test session cleanup (verify LS resources deleted)
- [ ] Test without LS configured (regression)
- [ ] Verify Sentry traces appear

---

## Rollback Plan

If issues occur:
1. Keep old `LangServerUtil.java` until validation complete
2. Feature can be disabled by removing `langserver-image` from AppDefinition
3. Old Docker images (with ports 5555/5556) remain available
4. Extension can fall back to per-language env vars

---

## Success Criteria

- [x] Java LS architecture implemented
- [x] Rust LS architecture implemented
- [x] Both Eager and Lazy paths implemented
- [x] Sentry instrumentation added for LS creation spans
- [x] Session cleanup via OwnerReference (K8s GC)
- [x] No regressions for sessions without LS (logic skips if no langserver-image)
- [x] Adding new language requires only registry entry + Docker image
- [ ] K8s integration testing (requires cluster)

---

## Notes

- **RWX PVC**: Ensure storage class supports ReadWriteMany
- **Node Affinity**: If RWX not available, may need pod affinity rules
- **Port 5000**: All LS images must be updated before operator changes
- **Backward Compatibility**: Extension supports both old and new env vars
