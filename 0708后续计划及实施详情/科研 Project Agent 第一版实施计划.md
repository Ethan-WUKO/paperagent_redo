# 科研 Project Agent 第一版实施计划

> 文档状态：执行中
> 创建日期：2026-07-12
> 已审查工程基线：`da8eaf6`（Project Markdown 与输出格式修复）
> Worker 1 验收基线：`e1f733d`（离线发布门与本地验收矩阵）
> Worker 启动基线：以串行任务包中冻结的完整 `HEAD` 为准
> 当前发布状态：`ENGINEERING_GATE_PASSED / LOCAL_ACCEPTANCE_PENDING`
> 设计依据：《通用 Agent Runtime 设计》《通用 Agent Runtime 实施方案与进度》

> 夜间串行进度：前序 Project 变更与 Worker 1“MVP 本地验收矩阵”均已完成主对话复审。Worker 1 独立发布门为 Java 170/170、frontend 6/6、exit 0，并通过测试层隔离真实模型与文献 provider。下一步仅启动 Worker 2“科研工具与结构化索引契约”。

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

### 阶段 0：MVP 本地验收与基线稳定

状态：`IN_PROGRESS`

- 保留自动发布门已通过结论。
- 完成用户本地真实科研场景验收。
- 建立缺陷清单与回归用例。
- 修复 P0/P1 后形成新的稳定基线 commit。

退出条件：

- 当前开发对话全部结束。
- 变更完成主对话复审。
- 工作区无未归属修改。
- 形成新的基线 commit。

### 阶段 1：科研语义工具与索引

冻结工具契约和索引模型，实现第一批只读科研工具并接入 ToolDescriptor、策略、证据和预算。不包含写文件、命令执行和多 Agent。

### 阶段 2：Task Workspace 与持久化生命周期

统一任务运行空间和关键生命周期语义，建立 checkpoint 和恢复基础。

### 阶段 3：沙箱与候选修改

建立工作副本，支持论文、代码、BibTeX 和配置候选修改，完成结构、版本和证据验证，仍不直接应用真实 Project。

### 阶段 4：用户确认、版本应用与回滚

实现逐条 diff 审核、接受、拒绝、冲突检测、原子生成新 Project 版本、回滚和导出。

### 阶段 5：跨材料科研编排

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

当前首个基线：

```text
7ef6ecff0cd831abf1345aaf29c86436b75f5769
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

### Worker 1：MVP 本地验收矩阵

前置条件：当前 Project 开发对话结束、主对话复审并形成新基线。

状态：`ACCEPTED_ENGINEERING_GATE / LOCAL_MANUAL_ACCEPTANCE_PENDING`

- 整理真实验收场景。
- 补充自动化辅助测试。
- 不默认修改生产代码。
- 发现生产缺陷即停止并报告。

### Worker 2：科研语义工具契约

前置条件：Worker 1 验收通过。

状态：`READY_AFTER_BASELINE_COMMIT`

- 定义第一批工具输入、输出、权限、Evidence、重复和预算。
- 定义结构化索引最小模型。
- 先完成契约和测试基线，不批量实现。

### Worker 3：第一批只读科研工具

前置条件：Worker 2 验收通过。

- LaTeX outline。
- BibTeX audit。
- Code symbols。
- Experiment summary。
- Cross-material search。

### Worker 4：Task Workspace 设计与最小骨架

前置条件：Worker 3 验收通过。

- 任务工作空间契约。
- 基线 ProjectVersion。
- Evidence、Artifact、Candidate 引用。
- 生命周期 checkpoint 边界。

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
