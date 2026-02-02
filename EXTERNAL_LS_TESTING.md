# External Language Server Kubernetes Testing Guide

This document provides comprehensive testing instructions for the External Language Server Kubernetes integration implemented in PR #70.

## Overview

This PR implements the Kubernetes operator support for external language servers, enabling:
- Automatic creation of language server deployments and services
- Environment variable injection into Theia containers
- Shared workspace access via ReadWriteMany PVCs
- Automatic cleanup via Kubernetes OwnerReferences
- Support for both Lazy and Eager session strategies

---

## Prerequisites

- Kubernetes cluster (v1.20+)
- kubectl configured and connected to cluster
- ReadWriteMany storage class available (check with: `kubectl get storageclass`)
- Theia Cloud operator built and deployed
- Language server Docker images pushed to registry:
  - `ghcr.io/ls1intum/theia/langserver-java:latest`
  - `ghcr.io/ls1intum/theia/langserver-rust:latest`
  - `ghcr.io/ls1intum/theia/theia-no-ls:latest`

---

## Phase 1: Verify Storage Class

```bash
# Check for ReadWriteMany storage class
kubectl get storageclass

# Look for access modes
kubectl describe storageclass <your-storage-class-name>

# Should show:
# VolumeBindingMode: WaitForFirstConsumer or Immediate
# Parameters should support RWX (ReadWriteMany)

# Common RWX storage classes:
# - AWS EFS: efs-sc
# - Azure Files: azurefile
# - NFS: nfs-client
# - Longhorn: longhorn
```

**If no RWX storage class is available**, you may need to:
1. Install an NFS provisioner
2. Use node affinity to ensure Theia and LS pods run on the same node
3. Or use a cloud provider's RWX storage solution

---

## Phase 2: Deploy/Update Theia Cloud Operator

```bash
cd theia-cloud

# Build operator (adjust based on your build process)
# Example:
mvn clean install -DskipTests

# Deploy updated operator
# (Follow your existing deployment process)

# Verify operator is running
kubectl get pods -n theia-cloud-operator
# Should show operator pod in Running state

# Check operator logs for startup
kubectl logs -n theia-cloud-operator deployment/theia-cloud-operator --tail=50
# Should NOT show any errors
```

---

## Test Case 1: Lazy Session with Java Language Server

### 1.1 Create AppDefinition

```bash
cat <<EOF | kubectl apply -f -
apiVersion: theia.cloud/v1beta9
kind: AppDefinition
metadata:
  name: java-ide-test
  namespace: theia-cloud
spec:
  name: java-ide-test
  image: ghcr.io/ls1intum/theia/theia-no-ls:latest
  port: 3000
  minInstances: 0
  timeout: 30
  options:
    langserver-image: "ghcr.io/ls1intum/theia/langserver-java:latest"
    langserver-language: "java"
    langserver-cpu-limit: "1000m"
    langserver-memory-limit: "2Gi"
    langserver-cpu-request: "200m"
    langserver-memory-request: "512Mi"
EOF
```

### 1.2 Create Session

```bash
cat <<EOF | kubectl apply -f -
apiVersion: theia.cloud/v1beta9
kind: Session
metadata:
  name: test-java-session
  namespace: theia-cloud
spec:
  name: test-java-session
  appDefinition: java-ide-test
  user: test-user
  url: java-test
EOF
```

### 1.3 Verify Resources Created

```bash
# Watch session become ready
kubectl get session test-java-session -n theia-cloud -w
# Wait for status to show Ready

# List all resources for this session
kubectl get all -n theia-cloud -l theia-cloud.io/session=test-java-session

# Expected output:
# NAME                                       READY   STATUS    RESTARTS   AGE
# pod/test-java-session-xxx                  1/1     Running   0          30s
# pod/test-java-session-ls-xxx               1/1     Running   0          30s
#
# NAME                           TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)    AGE
# service/test-java-session      ClusterIP   10.96.xxx.xxx   <none>        3000/TCP   30s
# service/test-java-session-ls   ClusterIP   10.96.xxx.xxx   <none>        5000/TCP   30s
#
# NAME                                   READY   UP-TO-DATE   AVAILABLE   AGE
# deployment.apps/test-java-session      1/1     1            1           30s
# deployment.apps/test-java-session-ls   1/1     1            1           30s
```

### 1.4 Verify Language Server Deployment Configuration

```bash
# Check LS deployment details
kubectl describe deployment test-java-session-ls -n theia-cloud

# Verify:
# ✅ Image: ghcr.io/ls1intum/theia/langserver-java:latest
# ✅ Port: 5000 (containerPort)
# ✅ Resources:
#    Limits: cpu: 1000m, memory: 2Gi
#    Requests: cpu: 200m, memory: 512Mi
# ✅ Labels:
#    theia-cloud.io/component: language-server
#    theia-cloud.io/session: test-java-session
# ✅ OwnerReferences: points to Session resource

# Check if PVC is mounted (if workspace persistence is enabled)
kubectl get deployment test-java-session-ls -n theia-cloud -o jsonpath='{.spec.template.spec.volumes}' | jq

# Should show workspace-data volume if PVC is configured

# Check WORKSPACE_PATH environment variable
kubectl get deployment test-java-session-ls -n theia-cloud \
  -o jsonpath='{.spec.template.spec.containers[0].env}' | jq
# Should include: {"name": "WORKSPACE_PATH", "value": "/home/project"}
```

### 1.5 Verify Theia Deployment Environment Variables

```bash
# Check Theia env vars
kubectl get deployment test-java-session -n theia-cloud \
  -o jsonpath='{.spec.template.spec.containers[0].env}' | jq

# Should include:
# - LS_JAVA_HOST=test-java-session-ls
# - LS_JAVA_PORT=5000
# - LS_HOST=test-java-session-ls  (generic)
# - LS_PORT=5000  (generic)
# - LS_LANGUAGE=java
```

### 1.6 Verify Language Server Logs

```bash
# Check LS startup logs
kubectl logs -n theia-cloud deployment/test-java-session-ls --tail=100

# Expected output:
# [LS-JAVA] Starting Java Language Server on port 5000 with workspace /home/project
# (Java initialization logs...)

# Check for errors
kubectl logs -n theia-cloud deployment/test-java-session-ls | grep -i error

# Should be empty or only show benign warnings
```

### 1.7 Test Language Server Functionality

```bash
# Get Ingress URL
kubectl get ingress -n theia-cloud -l theia-cloud.io/session=test-java-session

# Access Theia IDE at the ingress URL
# Example: https://java-test.your-domain.com
```

**In Theia IDE:**

1. Create `HelloWorld.java`:
```java
public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("Hello from Kubernetes!");
    }
}
```

2. Verify features:
   - ✅ Syntax highlighting
   - ✅ Code completion (type `System.` and wait)
   - ✅ Error detection (type invalid syntax)
   - ✅ Hover documentation
   - ✅ Go to definition

3. Check browser console (F12):
   - Look for `[LSP-EXT] Connected to java LS` message

### 1.8 Test Shared Workspace

```bash
# Create file from LS pod
kubectl exec -n theia-cloud deployment/test-java-session-ls -- \
  sh -c 'echo "Created by LS pod" > /home/project/test-from-ls.txt'

# Verify in Theia IDE:
# - Refresh file explorer
# - Should see test-from-ls.txt
# - Open it, should contain "Created by LS pod"

# Create file from Theia pod
kubectl exec -n theia-cloud deployment/test-java-session -- \
  sh -c 'echo "Created by Theia pod" > /home/project/test-from-theia.txt'

# Verify LS can see it
kubectl exec -n theia-cloud deployment/test-java-session-ls -- \
  cat /home/project/test-from-theia.txt
# Should output: "Created by Theia pod"
```

### 1.9 Test Session Deletion and Cleanup

```bash
# Delete session
kubectl delete session test-java-session -n theia-cloud

# Watch resources being deleted (should happen automatically via OwnerReferences)
kubectl get all -n theia-cloud -l theia-cloud.io/session=test-java-session -w

# After ~30 seconds, verify all resources are gone
kubectl get all -n theia-cloud -l theia-cloud.io/session=test-java-session
# Should return: No resources found

# Verify PVC cleanup (if PVCs are created per-session)
kubectl get pvc -n theia-cloud -l theia-cloud.io/session=test-java-session
# Should be deleted if configured with OwnerReference
```

---

## Test Case 2: Eager Session with Rust Language Server

### 2.1 Create AppDefinition with Eager Start

```bash
cat <<EOF | kubectl apply -f -
apiVersion: theia.cloud/v1beta9
kind: AppDefinition
metadata:
  name: rust-ide-test
  namespace: theia-cloud
spec:
  name: rust-ide-test
  image: ghcr.io/ls1intum/theia/theia-no-ls:latest
  port: 3000
  minInstances: 2  # Enable eager pre-warming
  timeout: 30
  options:
    langserver-image: "ghcr.io/ls1intum/theia/langserver-rust:latest"
    langserver-language: "rust"
EOF
```

### 2.2 Wait for Pool Pre-warming

```bash
# Wait 30-60 seconds for pool to create instances

# Check pool instances
kubectl get deployment -n theia-cloud -l theia-cloud.io/appdefinition=rust-ide-test

# Should see 2 Theia deployments (but NO LS deployments yet - created on session request)

# Verify deployments have theia-cloud.io/instance-id label
kubectl get deployment -n theia-cloud -l theia-cloud.io/appdefinition=rust-ide-test \
  -o jsonpath='{.items[*].metadata.labels}' | jq
```

### 2.3 Create Session to Reserve Instance

```bash
cat <<EOF | kubectl apply -f -
apiVersion: theia.cloud/v1beta9
kind: Session
metadata:
  name: test-rust-session
  namespace: theia-cloud
spec:
  name: test-rust-session
  appDefinition: rust-ide-test
  user: test-user
  url: rust-test
EOF
```

### 2.4 Verify LS Created for Reserved Instance

```bash
# Watch session become ready
kubectl get session test-rust-session -n theia-cloud -w

# Check all resources
kubectl get all -n theia-cloud -l theia-cloud.io/session=test-rust-session

# Should now see:
# - Theia deployment (was pre-warmed, now labeled with session)
# - LS deployment (newly created)
# - Theia service
# - LS service
```

### 2.5 Verify Env Vars Patched into Existing Deployment

```bash
# The pre-warmed Theia deployment should now have LS env vars
kubectl get deployment -n theia-cloud -l theia-cloud.io/session=test-rust-session \
  -o jsonpath='{.items[0].spec.template.spec.containers[0].env}' | jq

# Should include:
# - LS_RUST_HOST=test-rust-session-ls
# - LS_RUST_PORT=5000
# - LS_HOST=test-rust-session-ls
# - LS_PORT=5000
# - LS_LANGUAGE=rust
```

### 2.6 Test Rust Functionality

Access Theia IDE and create a Rust project:

**Create `Cargo.toml`:**
```toml
[package]
name = "hello"
version = "0.1.0"
edition = "2021"
```

**Create `src/main.rs`:**
```rust
fn main() {
    println!("Hello from Kubernetes!");
}
```

**Verify:**
- ✅ Rust syntax highlighting
- ✅ Code completion for std library
- ✅ Error detection for invalid syntax
- ✅ Hover docs

### 2.7 Cleanup

```bash
kubectl delete session test-rust-session -n theia-cloud
kubectl delete appdefinition rust-ide-test -n theia-cloud

# Verify all resources deleted
kubectl get all -n theia-cloud -l theia-cloud.io/appdefinition=rust-ide-test
```

---

## Test Case 3: Session Without Language Server (Regression)

### 3.1 Create Plain AppDefinition

```bash
cat <<EOF | kubectl apply -f -
apiVersion: theia.cloud/v1beta9
kind: AppDefinition
metadata:
  name: plain-ide-test
  namespace: theia-cloud
spec:
  name: plain-ide-test
  image: ghcr.io/ls1intum/theia/theia-no-ls:latest
  port: 3000
  minInstances: 0
  timeout: 30
  # NO langserver-image option
EOF
```

### 3.2 Create Session

```bash
cat <<EOF | kubectl apply -f -
apiVersion: theia.cloud/v1beta9
kind: Session
metadata:
  name: test-plain-session
  namespace: theia-cloud
spec:
  name: test-plain-session
  appDefinition: plain-ide-test
  user: test-user
EOF
```

### 3.3 Verify NO LS Resources Created

```bash
kubectl get all -n theia-cloud -l theia-cloud.io/session=test-plain-session

# Should ONLY see:
# - Theia deployment
# - Theia service
# - NO LS deployment
# - NO LS service

# Explicitly check for LS resources
kubectl get deployment,service -n theia-cloud \
  -l theia-cloud.io/component=language-server,theia-cloud.io/session=test-plain-session
# Should return: No resources found
```

### 3.4 Verify Theia Works Normally

```bash
# Check Theia is running
kubectl get deployment test-plain-session -n theia-cloud

# Check env vars (should NOT have LS_* vars)
kubectl get deployment test-plain-session -n theia-cloud \
  -o jsonpath='{.spec.template.spec.containers[0].env}' | jq | grep LS_
# Should return empty
```

### 3.5 Cleanup

```bash
kubectl delete session test-plain-session -n theia-cloud
kubectl delete appdefinition plain-ide-test -n theia-cloud
```

---

## Test Case 4: Custom Resource Overrides

### 4.1 Test Resource Limit Overrides

```bash
cat <<EOF | kubectl apply -f -
apiVersion: theia.cloud/v1beta9
kind: AppDefinition
metadata:
  name: java-custom-resources
  namespace: theia-cloud
spec:
  name: java-custom-resources
  image: ghcr.io/ls1intum/theia/theia-no-ls:latest
  port: 3000
  minInstances: 0
  options:
    langserver-image: "ghcr.io/ls1intum/theia/langserver-java:latest"
    langserver-language: "java"
    langserver-cpu-limit: "2000m"
    langserver-memory-limit: "4Gi"
    langserver-cpu-request: "500m"
    langserver-memory-request: "1Gi"
EOF

cat <<EOF | kubectl apply -f -
apiVersion: theia.cloud/v1beta9
kind: Session
metadata:
  name: test-custom-resources
  namespace: theia-cloud
spec:
  name: test-custom-resources
  appDefinition: java-custom-resources
  user: test-user
EOF

# Wait for ready
kubectl wait --for=condition=Ready session/test-custom-resources -n theia-cloud --timeout=300s

# Verify custom resources
kubectl get deployment test-custom-resources-ls -n theia-cloud \
  -o jsonpath='{.spec.template.spec.containers[0].resources}' | jq

# Should show:
# {
#   "limits": {
#     "cpu": "2000m",
#     "memory": "4Gi"
#   },
#   "requests": {
#     "cpu": "500m",
#     "memory": "1Gi"
#   }
# }

# Cleanup
kubectl delete session test-custom-resources -n theia-cloud
kubectl delete appdefinition java-custom-resources -n theia-cloud
```

---

## Test Case 5: Sentry Trace Verification

If Sentry is configured in your operator:

```bash
# Create a session and check Sentry dashboard

# Expected transactions in Sentry:
# - session.added (with strategy: lazy or eager)
#   - ls.create (child span)
#     - ls.service.create (grandchild)
#     - ls.deployment.create (grandchild)
#   - ls.inject_env or ls.patch_env (child span)

# Expected tags:
# - language: java/rust
# - session.name: test-java-session
# - outcome: success

# Expected data:
# - session: session-name
# - image: langserver-image
# - ls_service: service-name
# - ls_deployment: deployment-name
```

Check your Sentry dashboard for these traces after creating sessions.

---

## Troubleshooting

### LS Deployment Not Created

1. **Check operator logs**:
   ```bash
   kubectl logs -n theia-cloud-operator deployment/theia-cloud-operator --tail=200
   ```
   Look for `[LS]` prefixed log messages.

2. **Check session status**:
   ```bash
   kubectl describe session test-java-session -n theia-cloud
   ```
   Look for error messages in status or events.

3. **Verify AppDefinition has langserver-image**:
   ```bash
   kubectl get appdefinition java-ide-test -n theia-cloud -o jsonpath='{.spec.options}' | jq
   ```

### LS Pod Not Starting

1. **Check pod events**:
   ```bash
   kubectl describe pod -n theia-cloud -l theia-cloud.io/component=language-server
   ```

2. **Check image pull status**:
   ```bash
   kubectl get pod -n theia-cloud -l theia-cloud.io/component=language-server
   ```
   Look for `ImagePullBackOff` or `ErrImagePull`.

3. **Check resource quotas**:
   ```bash
   kubectl describe resourcequota -n theia-cloud
   ```

### Workspace Not Shared

1. **Check PVC exists and is RWX**:
   ```bash
   kubectl get pvc -n theia-cloud
   kubectl describe pvc <pvc-name> -n theia-cloud
   ```
   Verify `Access Modes: RWX` or `ReadWriteMany`.

2. **Check volume mounts**:
   ```bash
   # Theia deployment
   kubectl get deployment test-java-session -n theia-cloud \
     -o jsonpath='{.spec.template.spec.volumes}' | jq
   
   # LS deployment
   kubectl get deployment test-java-session-ls -n theia-cloud \
     -o jsonpath='{.spec.template.spec.volumes}' | jq
   ```

3. **Check WORKSPACE_PATH**:
   ```bash
   kubectl exec -n theia-cloud deployment/test-java-session-ls -- env | grep WORKSPACE
   ```

### Connection Issues

1. **Verify service DNS resolution**:
   ```bash
   kubectl exec -n theia-cloud deployment/test-java-session -- \
     nslookup test-java-session-ls
   ```

2. **Test network connectivity**:
   ```bash
   kubectl exec -n theia-cloud deployment/test-java-session -- \
     nc -zv test-java-session-ls 5000
   ```

3. **Check service endpoints**:
   ```bash
   kubectl get endpoints test-java-session-ls -n theia-cloud
   ```
   Should show the LS pod IP.

---

## Performance Testing

### Test LS Startup Time

```bash
# Create session and measure time to Ready
time kubectl wait --for=condition=Ready session/test-java-session -n theia-cloud --timeout=300s

# Check LS pod startup time
kubectl get pod -n theia-cloud -l theia-cloud.io/component=language-server \
  -o jsonpath='{.items[0].status.startTime}'
```

### Test Multiple Concurrent Sessions

```bash
# Create 5 sessions concurrently
for i in {1..5}; do
  cat <<EOF | kubectl apply -f - &
apiVersion: theia.cloud/v1beta9
kind: Session
metadata:
  name: load-test-$i
  namespace: theia-cloud
spec:
  name: load-test-$i
  appDefinition: java-ide-test
  user: user-$i
EOF
done

wait

# Monitor resources
kubectl get all -n theia-cloud -l theia-cloud.io/appdefinition=java-ide-test

# Cleanup
for i in {1..5}; do
  kubectl delete session load-test-$i -n theia-cloud &
done
wait
```

---

## Success Criteria

- ✅ Lazy sessions create LS deployment and service
- ✅ Eager sessions patch LS env vars into pre-warmed deployments
- ✅ LS deployments use correct image, port (5000), and resources
- ✅ Theia deployments have correct LS_* environment variables
- ✅ OwnerReferences ensure automatic cleanup on session deletion
- ✅ Sessions without langserver-image work normally (no LS created)
- ✅ Workspace files are accessible from both Theia and LS pods
- ✅ WORKSPACE_PATH environment variable is set correctly
- ✅ Code completion and language features work in Theia IDE
- ✅ No errors in operator or pod logs
- ✅ Sentry traces show successful LS operations (if Sentry enabled)

---

## Next Steps

After successful testing:

1. Update CI/CD pipelines to build and push LS images
2. Document for end users how to configure language servers in AppDefinitions
3. Consider adding health checks for LS pods
4. Monitor resource usage in production
5. Add metrics/dashboards for LS performance
