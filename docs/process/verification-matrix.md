# 验证矩阵

使用本矩阵为每个 issue 选择检查项。风险更高的 PR 可以增加更强的验证。

| 变更类型 | 必须验证 | 可选或按风险增加 |
| --- | --- | --- |
| 仅文档 | `git diff --check` | 链接预览或人工阅读 |
| GitHub 模板 | `git diff --check` | 必要时创建测试 issue/PR |
| Maven pom/config | `mvn -q -DskipTests validate` | `mvn test` |
| 后端 service | focused module tests | 完整 `mvn test` |
| API/controller | controller 或 integration tests | 手动 API smoke test |
| Flyway migration | API 模块测试和必要的 H2 兼容验证 | 真实 MySQL migration 检查 |
| 前端 UI | `$env:CI='true'; pnpm build` | 浏览器/手动工作流检查 |
| 前端状态机 | build + focused logic 或手动状态验证 | 截图或短录屏 |
| Agent runtime | focused service tests + 手动/eval cases | 端到端聊天运行 |
| RAG 检索 | 检索 eval cases | 真实 ES/embedding 环境测试 |
| 文献推荐 | 真实性和去重 eval cases | 外部来源 smoke test |
| 论文润色 | focused paper tests + artifact 检查 | 端到端论文任务运行 |
| Kafka 任务分发 | producer/consumer focused tests | 本地 Docker Kafka smoke test |
| SSE/event stream | 事件 sequence 和终态测试 | 重连/浏览器手动检查 |
| 取消能力 | cancel 状态和 partial artifact 检查 | 长任务手动取消场景 |

## 默认命令

后端 validate：

```powershell
mvn -q -DskipTests validate
```

后端完整测试：

```powershell
mvn test
```

前端 build：

```powershell
cd frontend
$env:CI='true'
pnpm build
```

空白检查：

```powershell
git diff --check
```

## 合并规则

PR 只有满足以下任一条件才可以合并：

1. 必须验证项已通过。
2. 跳过的检查已有明确理由，风险可接受，并在需要时创建了后续 issue。

## 阶段纪律

开始实现前必须确认：

1. issue 属于 roadmap 的哪个阶段。
2. issue 是设计、spike、实现、测试还是文档任务。
3. 哪些非目标用于防止范围膨胀。
