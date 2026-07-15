# 科研 Project Agent 第一版实施计划

> 文档状态：当前执行权威计划
> 创建日期：2026-07-12
> 最近同步：2026-07-15
> 已审查工程基线：`1c40159`（Worker 5 Task Workspace 与短期工作记忆）
> Worker 1 验收基线：`e1f733d`（离线发布门与本地验收矩阵）
> Worker 2 契约工程基线：`8e274ab`（科研工具与结构化索引纯契约）
> Worker 3 只读工具工程基线：`1fc1e0f`（五个受治理科研工具与 Evidence 闭环）
> 当前工程基线：`1c40159`（L0 Task Workspace、受治理短期记忆与统一运行投影接入）
> Worker 启动基线：以串行任务包中冻结的完整 `HEAD` 为准
> 当前发布状态：`READ_ONLY_PROJECT_TASK_WORKSPACE_ENGINEERING_ACCEPTED / FIRST_VERSION_IN_PROGRESS`
> 设计依据：《通用 Agent Runtime 设计》《Agent 对比分析与后续改造建议》

> 当前进度：Worker 1 至 Worker 5 已完成主对话复审。用户已完成 Project 文件树/预览、五个科研工具和 Plan 关键场景测试；`56e6b5c` 已加入浏览器文件夹上传、托管对象存储、Project 会话与 Plan 展示，并修复规划 JSON 截断、步骤 Verifier 截断、依赖证据复用和受控 PARTIAL。Worker 4 基线 `ff6f6e5` 统一了 Chat/ReAct/Plan 的 run identity、status/phase/outcome、canonical answer 与 PARTIAL/取消/失败语义。Worker 5 基线 `1c40159` 在该投影上增加 L0 Task Workspace，保存目标、成功条件、计划引用、观测步骤摘要、剩余工作和有界短期记忆；任意 JSON 快照中的记忆只能降级为明确标记的非权威审计摘要，不能伪造 Evidence、Candidate、Artifact、失败结果或工具观察。独立复审结果：Worker 5 定向 29/29、Spring/Controller/Project 垂直回归 63/63、完整 `yanban-api` reactor 聚合 552 项零失败（8 项既有条件跳过），CLI 构建通过。MVP 发布门主体 203 项 Java 测试与 6 项前端测试通过，但脚本仍因基线遗留的绝对路径创建用例被禁用而返回 NO_GO；该测试治理项不是 Worker 4/5 回归，发布前必须单独收口。该结论不表示持久化 checkpoint/重启恢复、ProjectVersion、长期记忆主链、沙箱或安全应用已经完成。

## 1. 目标与边界

第一版要把当前只读 Project MVP 建设为可审查、可恢复、可安全修改的科研 Project Agent：

1. 理解用户上传的完整科研 Project。
2. 联合分析论文、代码、实验结果、配置、报告和文献。
3. 生成带证据的论文、代码、BibTeX 和实验配置候选修改。
4. 所有修改先进入隔离工作副本，不覆盖原始文件。
5. 用户能够查看、接受、拒绝、回滚并导出新的 Project 版本。

第一版不追求无人监督的自主科研循环。Pro 模式只能在沙箱、生命周期、证据、验证和回滚通过真实验收后启动。

目标产品不是单一论文润色器，也不是只会聊天和调用少量工具的助手，而是：

> 以用户授权的科研 Project 为中心，使用 ReAct、Plan-and-Execute、受限 Reflection 和后续受控 Worker 完成可审计科研任务，在沙箱中生成并验证候选修改，由用户决定如何形成新的 Project 版本。

## 2. 使用模式

### 2.1 普通对话

普通对话只能使用用户上传文件、知识库、受控长期记忆和允许的外部检索工具，不能根据用户电脑上的任意绝对路径读取文件。

### 2.2 Project 第一版

Web 服务以“上传文件夹或 ZIP”为主要入口：

1. 浏览器上传目录并保留相对结构。
2. 后端保存不可变原始版本并生成 manifest。
3. 系统建立结构化科研索引。
4. Agent 在只读原始版本和隔离任务工作区中执行。
5. 修改形成 Candidate ChangeSet。
6. 用户确认后生成新的 Project 版本。

本地单机部署可以保留绝对路径绑定，但它属于部署能力，不是 Web 模式读取用户电脑文件的通用方案。

### 2.3 Pro 模式

Pro 模式允许系统围绕用户初始目标，在隔离工作副本中自主修改论文、代码、实验配置和科研材料，运行受控实验并迭代优化，最终形成论文与可复现 Project。

Pro 模式仍必须具备：

- 不可变原始版本。
- 隔离分支或沙箱副本。
- 命令、网络、资源和时间预算。
- 阶段检查点。
- 基线 hash 与冲突检测。
- 实验数据真实性约束。
- 全量审计、自动回滚与人工接管。

## 3. 当前能力与缺口

| 能力 | 当前状态 | 第一版要求 |
|---|---|---|
| Runtime Coordinator | 已完成基础统一 | 增加复杂度驱动的自动策略选择 |
| ReAct | 已有 `SINGLE_STEP_REACT` | 扩展科研语义工具与停止条件 |
| Plan-and-Execute | 已有受信 Plan API | 支持科研任务自动建计划与步骤验证 |
| Reflection | 已有最多一次不扩权修复 | 增加论文、代码和跨材料领域 Verifier |
| 多 Agent | 只有协议设计 | 第一版后期仅验证受控只读 Worker |
| Project 工具 | manifest/read/search | 增加 LaTeX、BibTeX、代码和实验语义工具 |
| Project 索引 | manifest 与文本检索 | 增加章节、符号、引用和实验结构索引 |
| Project 输入 | 浏览器文件夹上传与托管存储已接入 | 增加不可变 ProjectVersion、多版本历史、导出和回滚 |
| 文件修改 | 仅 Candidate、NOT_APPLIED | 增加沙箱、diff、确认、应用和回滚 |
| 生命周期 | L1、进程内任务为主 | 建立持久化托管、checkpoint 和恢复 |
| 短期记忆 | 会话、摘要、ContextPackage | 增加任务工作记忆和结构化发现 |
| 长期记忆 | 设施存在，主链路保守关闭 | 增加来源、范围、版本和用户确认 |
| 沙箱 | 尚未实现完整执行沙箱 | 在开放写入和命令前完成 |
| 评测 | 确定性安全门已完成 | 增加真实科研 Project 端到端评测 |

## 4. 冻结安全原则

1. 原始 Project 版本不可变。
2. 第一版不允许模型直接覆盖用户真实文件。
3. 所有写入只发生在沙箱或受控工作副本。
4. Candidate ChangeSet 默认 `NOT_APPLIED`。
5. 应用前验证 projectId、相对路径、base hash、用户身份和确认记录。
6. 论文、代码、实验和工具输出均为不可信数据，不能提升系统权限。
7. 命令执行、网络访问和密钥使用必须由策略授权。
8. 实验结果必须绑定命令、代码版本、配置、输入和产物 hash。
9. 不得把未运行实验写成已完成，不得生成虚假数据填入论文。
10. Worker 默认只读，同一文件任何时刻只能有一个写者。
11. 用户外部修改优先；基线变化后候选修改必须标记 `STALE`。
12. 自然语言回答不能覆盖结构化失败、证据不足或预算终止状态。

## 5. Agent 执行模型

### 5.1 策略选择

```text
信息充分且无工具需求 -> DIRECT
单文件或探索性任务 -> REACT
跨文件且有明确交付物 -> PLAN_EXECUTE
高价值产物 -> 确定性验证 + 最多一次 Reflection
独立、可验证且资源隔离的子目标 -> 后续受控 Worker
```

Coordinator 应综合文件数量、领域数量、风险、预计时间、成功条件和是否需要写入，不能因为存在工具就永远进入 ReAct。

### 5.2 完成定义

只有以下条件同时满足时才能声明完成：

- 必要步骤已进入终态。
- 必需工具真实成功。
- 证据引用存在且版本有效。
- 候选修改通过结构和领域规则验证。
- 用户确认或明确保持 `NOT_APPLIED`。
- 最终产物和审计事件已经持久化。

## 6. 科研语义工具

### 6.1 第一批只读工具

- `project_latex_outline`：章节、公式、标签、引用和浮动体目录。
- `project_bibtex_audit`：条目、重复、缺字段、未引用和缺失引用键。
- `project_code_symbols`：类、函数、入口、参数和模块关系。
- `project_experiment_summary`：配置、CSV 指标、报告和结果文件摘要。
- `project_cross_material_search`：跨论文、代码、配置和报告查找同一概念。

### 6.2 第二批分析能力

- 论文公式与代码实现对照。
- 论文参数与实验配置对照。
- 论文表格与结果文件对照。
- 文献证据与具体论断对照。
- LaTeX 结构与引用完整性验证。
- 代码静态检查结果标准化。

### 6.3 候选修改能力

- 论文局部补丁。
- 代码补丁。
- BibTeX 变更。
- 实验配置变更。
- 带 base hash 和 EvidenceRefs 的 Candidate ChangeSet。

候选修改能力不等于应用能力。

## 7. 结构化 Project 索引

每个 Project 版本至少维护：

```text
ProjectVersion
FileManifest
LatexSectionIndex
LatexFormulaAndReferenceIndex
BibEntryIndex
CodeSymbolIndex
CodeDependencyIndex
ExperimentAssetIndex
CrossMaterialLinkIndex
```

索引项必须携带 Project 版本、相对路径、文件 hash、范围、解析器版本、来源和置信度。大文件不得整文件无差别发送给模型。

## 8. Task Workspace 与生命周期

Project 是长期材料空间；Task Workspace 是一次 Agent 执行的隔离工作空间，保存：

- 目标、成功条件和 Project 基线版本。
- 计划、步骤、已读取文件和证据。
- 可审计事实和待验证假设。
- 工具输出引用、候选修改和验证结果。
- 用户确认、最终产物和审计事件。

目标生命周期：

```text
CREATED
-> PLANNING
-> RUNNING
-> WAITING_INPUT / WAITING_CONFIRMATION
-> PAUSED / RESUMING
-> VERIFYING
-> FINALIZING
-> COMPLETED / PARTIAL / FAILED / CANCELLED
```

第一版需要从 L1 逐步升级到可重启恢复的持久化托管，包括任务队列、Worker lease、heartbeat、checkpoint、幂等执行和卡死任务回收。

## 9. 记忆系统

### 9.1 短期工作记忆

只保存可审计内容，不保存模型内部思维链：

- 当前目标和计划。
- 已确认事实与证据。
- 待验证问题。
- 候选修改。
- 失败原因和剩余工作。

### 9.2 长期记忆

分为用户偏好、Project 事实与术语、用户确认的研究决策、经验证的领域知识和可复用任务经验。

每条长期记忆必须包含 scope、source、provenance、confidence、projectVersion、userConfirmed 和过期策略。默认不自动保存模型猜测。

## 10. 沙箱与安全修改

第一版沙箱至少提供：

- 基于 Project 版本创建工作副本。
- 文件读写范围。
- 命令 allowlist。
- CPU、内存、时间和输出预算。
- 默认关闭网络。
- 环境变量和密钥隔离。
- 进程终止、临时文件清理和产物收集。

安全修改流程：

```text
原始 Project 版本
-> 沙箱工作副本
-> Agent 修改
-> 静态检查/测试/可选编译
-> Candidate ChangeSet
-> 用户逐条确认
-> base hash 再校验
-> 生成新 Project 版本
-> 保留回滚点
```

## 11. 联合任务示例

用户上传论文、BibTeX、算法代码、实验代码、CSV 结果和实验报告，并要求检查方法、公式、代码与实验是否一致。

Plan：

1. 解析论文方法、公式和贡献。
2. 解析代码入口、目标函数、约束和参数。
3. 解析实验配置、CSV 和报告。
4. 建立公式、代码与实验关联。
5. 判断冲突来源，不能默认论文或代码任一方正确。
6. 生成论文、代码或配置的候选修改方案。
7. 在沙箱中验证修改。
8. 输出 diff、理由、证据和未解决问题。

系统必须允许结论为：

- 修改论文以准确描述当前实现。
- 修改代码以符合论文方法。
- 同时修改代码、论文和实验配置。
- 证据不足，需要用户确认研究意图。

## 12. 多 Agent 边界

第一版前期不实现自由多 Agent 调度。单 Agent 工具、生命周期和修改闭环稳定后，首个受控场景为：

- Worker A 只读分析代码。
- Worker B 只读分析论文。
- Worker C 只读分析实验与文献。
- 父 Agent 对照结果并生成唯一 Candidate ChangeSet。

禁止 Worker 自由创建无限子 Worker、复制整个主会话、并行修改同一文件或自行宣布父任务完成。

## 13. 实施阶段

### 阶段 0：只读 MVP、本地验收与基线稳定

状态：`ACCEPTED_FOR_FIRST_VERSION_FOUNDATION`

- 保留自动测试主体通过的证据；在遗留禁用用例收口前，不宣称完整发布门 GO。
- 用户已完成 Project 文件浏览、科研工具、ReAct 和 Plan 关键场景验收；后续阶段继续保留真实科研回归。
- 建立缺陷清单与回归用例。
- 修复 P0/P1 后形成新的稳定基线 commit。

退出条件：

- 当前开发对话全部结束。
- 变更完成主对话复审。
- 工作区无未归属修改。
- 形成新的基线 commit。

### 阶段 1：科研语义工具与索引

状态：`ACCEPTED_READ_ONLY_FOUNDATION`

冻结工具契约和索引模型，实现第一批只读科研工具并接入 ToolDescriptor、策略、证据和预算。不包含写文件、命令执行和多 Agent。

### 阶段 2A：统一 Task Run 与持久化生命周期

状态：`ACCEPTED_L0_L1_RUN_LIFECYCLE_FOUNDATION`

统一普通 Chat、Project Chat、ReAct 和 Plan 的单次运行抽象、权威状态、phase、outcome、事件和 canonical answer，建立取消、暂停、恢复、checkpoint 和重启恢复边界。

当前只完成统一语义与最小生产投影；持久化 checkpoint、lease、跨进程恢复和重启恢复仍属于后续实施范围，不得因状态模型已经统一而视为完成。

### 阶段 2B：Task Workspace 与短期工作记忆

保存当前目标、成功条件、计划、步骤、受信 Evidence、工具观察、失败原因、候选产物和剩余工作；统一上下文裁剪、观察复用和 checkpoint 恢复，不保存模型内部思维链。

### 阶段 2C：ProjectVersion 与版本证据

在现有文件夹上传和对象存储基础上增加不可变 ProjectVersion、版本化 manifest、索引版本、Evidence 绑定、版本比较和导出基础。任何 migration 必须先单独审查。

### 阶段 2D：长期记忆治理与受控接入

先完成用户可见的来源、scope、provenance、confidence、修正、删除、过期和注入审计，再把经过确认的长期记忆只读接入主链。禁止自动保存模型猜测或直接打开全量历史注入。

### 阶段 3：沙箱与候选修改

建立工作副本，支持论文、代码、BibTeX 和配置候选修改，完成结构、版本和证据验证，仍不直接应用真实 Project。

### 阶段 4：用户确认、版本应用与回滚

实现逐条 diff 审核、接受、拒绝、冲突检测、原子生成新 Project 版本、回滚和导出。

### 阶段 5：统一策略选择与跨材料科研编排

实现自动 ReAct/Plan 选择、论文代码实验文献联合分析、领域 Verifier 和受限 Reflection。

### 阶段 6：受控 Worker 验证

实现只读 WorkerTaskPacket、单写者父 Agent、结构化 WorkerResult 和跨材料差异报告。

### 阶段 7：Pro 模式研究

只有第一版通过真实科研 Project 验收后才能开始。

## 14. 串行 Worker 开发协议

每个 Worker 启动前记录：

```text
baselineCommit
objective
allowedFiles
forbiddenFiles
frozenContracts
requiredTests
stopConditions
```

当前基线：

```text
56e6b5cdc80b3b0cca9888f6bdd71ae1b56666f2
```

任何前序 Worker 的变更必须先完成主对话复审并提交，才能成为下一 Worker 的基线。

串行流程：

```text
Worker 开发
-> Worker 报告
-> 主对话静态审查
-> 主对话独立测试
-> CHANGES_REQUIRED 或 ACCEPTED
-> 修正与复审
-> 形成新 commit
-> 启动下一 Worker
```

同一工作区禁止两个开发 Worker 同时修改。主对话在 Worker 运行期间只允许只读监督，或修改明确不相交的独立规划文档。

出现 P0、契约冲突或大范围回归时停止新 Worker，使用该任务的 baseline commit 判断回滚范围；不使用未经审查的 `reset --hard`。

## 15. 当前串行执行队列

### 已完成：Worker 1 至 Worker 5

- Worker 1：`ACCEPTED_ENGINEERING_GATE`，MVP 本地验收矩阵和离线发布门隔离完成。
- Worker 2：`ACCEPTED / CONTRACT_ONLY`，科研工具和结构化索引契约完成，基线 `8e274ab`。
- Worker 3：`ACCEPTED_READ_ONLY_IMPLEMENTATION`，五个受治理科研工具完成，基线 `1fc1e0f`。
- 2026-07-15 收口基线：`56e6b5c`。
- Worker 4：`ACCEPTED_L0_L1_RUN_LIFECYCLE_FOUNDATION`，统一 Task Run 与生命周期投影完成，基线 `ff6f6e5`。
- Worker 5：`ACCEPTED_L0_TASK_WORKSPACE_FOUNDATION`，Task Workspace 与受治理短期工作记忆完成，基线 `1c40159`。

### Worker 4：统一 Task Run 与生命周期契约/最小骨架

状态：`ACCEPTED`

- 普通 Chat、Project Chat、ReAct、Plan 的统一 run 语义。
- 唯一权威 status/phase/outcome 映射。
- 统一 run trace、事件、canonical answer 和错误分类。
- 暂停、取消、恢复、重试和 checkpoint 边界。
- 只允许契约和最小骨架；如需 migration，先停下并单独评审。
- 已验证：定向 158/158；根聚合 8/8 模块通过，`yanban-api` 541 项零失败、8 项既有条件跳过。
- 已知发布门治理项：遗留绝对路径创建测试为 `@Disabled`，导致零跳过发布门保持 NO_GO；不得归因于 Worker 4，也不得在本 Worker 范围外顺手修改。

### Worker 5：Task Workspace 与短期工作记忆

状态：`ACCEPTED_L0_TASK_WORKSPACE_FOUNDATION`

- 当前目标、成功条件、计划、步骤和剩余工作。
- 受信 Evidence、工具观察、失败结果和 Candidate 引用。
- 上下文裁剪、观察复用、会话摘要和非权威快照的降级恢复边界。
- 不保存或展示模型内部思维链。
- 已按统一 run identity/status/phase/outcome/canonical answer 接入普通 Chat、ReAct、Project 和受信 Plan 路径，不改变 tool policy。
- 当前只保存计划引用与观测步骤摘要，不宣称恢复完整结构化 Plan。
- 持久性固定为 `L0_REQUEST_BOUND`；`checkpointAvailable=false`、`restartResumable=false`，不提供跨进程 checkpoint。
- 无完整性证明的 JSON 快照记忆统一降级为 `AUDIT_SUMMARY` 并标记 `recovered/untrusted`，不能恢复为权威 Evidence、Candidate、Artifact、失败或工具观察。
- 已验证：定向 29/29；Spring/Controller/Project 垂直回归 63/63；完整 `yanban-api` reactor 聚合 552 项零失败、8 项既有条件跳过；CLI 构建通过。

### Worker 6：ProjectVersion 与版本化 Evidence

状态：`NEXT_REQUIRES_EXPLICIT_START`

- 不可变 ProjectVersion 和版本化 manifest。
- 索引、Evidence、Artifact、Candidate 的版本绑定。
- 多版本历史、比较和导出基础。
- 不开放直接覆盖用户 Project。

### Worker 7：长期记忆治理与只读接入

状态：`QUEUED`

- 来源、scope、provenance、confidence、确认和过期契约。
- 用户查看、修正、删除与注入审计。
- 只读接入经过确认且与当前任务相关的记忆。
- 不自动写入模型推测，不注入已删除或越权记忆。

夜间无人值守时不启动沙箱写入、命令执行、自动应用或多 Agent 写入任务。

## 16. 审查与停止条件

主对话发现以下任一情况必须停止自动推进：

- 当前工作区存在另一开发任务正在运行。
- 变更文件超出 Worker 所有权。
- 基线 commit 与启动记录不一致。
- 冻结契约被改变。
- 测试依赖真实外部模型才能判断。
- 发现 P0 安全问题。
- 需要 migration 但方案未审查。
- 需要开放写文件、命令、网络或密钥权限。
- Worker 把 mock、预置数据或自然语言回答伪装成真实完成。
- 无法确认用户现有修改来源。

## 17. 第一版完成标准

1. 能上传并版本化完整文件夹。
2. 能结构化理解论文、代码、实验和文献。
3. 能自动选择 ReAct 或 Plan 完成跨材料分析。
4. 结论均带可追溯证据和文件版本。
5. 能生成论文与代码 Candidate ChangeSet。
6. 候选修改在沙箱中通过必要验证。
7. 用户可以逐条接受、拒绝和回滚。
8. 能生成新的完整 Project 版本并导出。
9. 长任务能够暂停、恢复、取消和明确失败。
10. 不发生越权读取、未授权写入、虚假实验或虚假完成。

满足这些条件后，才能规划 Pro 模式的自主研究循环。
