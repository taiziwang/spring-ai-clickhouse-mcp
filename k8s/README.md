# Kubernetes 部署（namespace: ckmcp）

把 `spring-ai-clickhouse-mcp:1.0.0` 以 **Deployment + NodePort** 方式部署到集群，
并附带一个配套的 ClickHouse 实例，使 `apply` 完即可端到端使用。

## 目录结构

| 文件 | 内容 |
|---|---|
| `00-namespace.yaml` | Namespace `ckmcp`（带 `app: ckmcp` 标签） |
| `01-config.yaml` | ConfigMap `ckmcp-config`（连接/限额）+ Secret `ckmcp-secret`（口令） |
| `02-clickhouse.yaml` | ClickHouse Deployment + ClusterIP Service `clickhouse:8123` |
| `03-mcp-deployment.yaml` | **MCP 服务 Deployment `ckmcp`**（image `spring-ai-clickhouse-mcp:1.0.0`） |
| `04-mcp-service.yaml` | **NodePort Service `ckmcp`**，`30801 → 8000` |
| `kustomization.yaml` | `kubectl apply -k k8s/` 的入口 |

所有资源统一带 `app: ckmcp` 标签，MCP 相关额外带 `component: mcp`，
ClickHouse 相关带 `component: clickhouse`。

## 前置：让集群能看到本地镜像

镜像没有推仓库，`imagePullPolicy: IfNotPresent`。按集群类型处理：

```bash
# Docker Desktop（本机实测环境）：直接共享本地镜像，无需额外操作
docker images spring-ai-clickhouse-mcp:1.0.0

# kind：
kind load docker-image spring-ai-clickhouse-mcp:1.0.0

# minikube：
minikube image load spring-ai-clickhouse-mcp:1.0.0

# 其他集群：推到镜像仓库，并把 03-mcp-deployment.yaml 的 image 改成带仓库地址的全名
```

镜像不在节点上时的表现是 `ImagePullBackOff`，`kubectl describe pod` 里能看到
`ErrImageNeverPull` / `pull access denied` —— 不是配置错误，是镜像没到节点。

## 部署

```bash
kubectl apply -k k8s/

kubectl -n ckmcp rollout status deploy/clickhouse --timeout=180s
kubectl -n ckmcp rollout status deploy/ckmcp --timeout=180s

kubectl -n ckmcp get pods,svc
```

预期：

```
NAME                          READY   STATUS    RESTARTS
ckmcp-xxxxxxxxx-xxxxx         1/1     Running   0
clickhouse-xxxxxxxxx-xxxxx    1/1     Running   0

NAME         TYPE        CLUSTER-IP      PORT(S)
ckmcp        NodePort    10.98.128.245   8000:30801/TCP
clickhouse   ClusterIP   10.110.35.223   8123/TCP,9000/TCP
```

## 访问

```bash
# 健康检查（会真实探测 ClickHouse，200 才算就绪）
curl http://localhost:30801/health          # → OK

# MCP 端点（Streamable HTTP）
#   http://localhost:30801/mcp
```

NodePort 是**每个节点都监听**的端口，所以用任意节点 IP 都能访问；
Docker Desktop 环境下节点就是本机，直接 `localhost:30801`。

> ⚠️ `30800` 在多数机器上已被别的 Service 占用（本机是 `dsh-agent`）。
> 若 `apply` 报 `provided port is already allocated`，先 `kubectl get svc -A` 看谁占了，
> 再把 `04-mcp-service.yaml` 里的 `nodePort` 改成空闲值。

### 客户端配置

```json
{
  "mcpServers": {
    "clickhouse": {
      "type": "http",
      "url": "http://localhost:30801/mcp"
    }
  }
}
```

## 验证

```bash
# 1. 灌测试数据（可选，只为跑 E2E）
kubectl -n ckmcp exec -i deploy/clickhouse -- \
  clickhouse-client --user mcp --password mcp123 --multiquery < tools/seed_clickhouse.sql

# 2. 跑完整 48 项 E2E（就是 docker 验证用的同一套用例）
python3 tools/e2e_clickhouse_test.py http://localhost:30801
```

本机实测结果：**48 项全部通过**；删除 MCP Pod 后 Deployment 自动重建，
`/health` 1 秒内恢复 200。

## 改配置

```bash
kubectl -n ckmcp edit configmap ckmcp-config
# ConfigMap 不会自动热加载（Spring 启动时读取），必须滚一次：
kubectl -n ckmcp rollout restart deploy/ckmcp
kubectl -n ckmcp rollout status deploy/ckmcp
```

连外部 ClickHouse 时：改 `ckmcp-config` 里的 `CLICKHOUSE_MCP_HOST/PORT/SECURE`，
并把 `02-clickhouse.yaml` 从 `kustomization.yaml` 的 resources 里去掉。

## 探针设计（别随手改）

| 探针 | 端点 | 原因 |
|---|---|---|
| `startupProbe` | `tcpSocket:8000` | JVM 启动期间 `/health` 会返回 503，用 TCP 才不会误判 |
| `readinessProbe` | `GET /health` | 503 表示「ClickHouse 不可达」→ 摘流量，这是**正确**语义 |
| `livenessProbe` | `tcpSocket:8000` | **只看进程活没活** |

⚠️ liveness 千万不要用 `/health`：ClickHouse 短暂不可达时它返回 503，
kubelet 会误判进程死亡并反复重启 Pod，把「下游抖动」放大成「自身雪崩」。

## 清理

```bash
kubectl delete -k k8s/
# 或只删工作负载、保留命名空间：
kubectl -n ckmcp delete deploy ckmcp clickhouse
```

## 上生产前要改的地方

1. **Secret**：现在口令是明文 `stringData`，换成 SealedSecrets / External Secrets / KMS。
2. **ClickHouse 存储**：现在是 `emptyDir`，Pod 重建即丢数据。生产用 PVC 或直接用
   ClickHouse Operator / Altinity chart 部署集群版。
3. **入口**：NodePort 无 TLS、无认证。建议换 Ingress + TLS，并在网关层加认证。
4. **账号**：给 MCP 配**只读账号**（`GRANT SELECT`），别用默认库的读写账号。
5. **副本与配额**：MCP 无状态可扩副本，但每个副本各自持有连接池（`pool-size`），
   扩之前先确认 ClickHouse 侧该账号的并发配额。
6. **NetworkPolicy**：只允许 `ckmcp` 命名空间内 MCP → ClickHouse 的 8123 流量。
7. **资源**：当前 requests 250m/512Mi、limits 1C/1Gi，按实际查询规模调整。
