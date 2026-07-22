# 0708 后续计划及实施详情文档索引

> 更新日期：2026-07-22
>
> 当前工程基线：GitHub `origin/main` 最新提交（Worker 20 至 Worker 23 已完成验收）
>
> 规则：先读“当前有效”，需要追溯证据时再读“历史归档”。历史文档不得覆盖当前权威设计和执行计划。

## 当前有效

1. [通用 Agent Runtime 设计](./当前有效/通用%20Agent%20Runtime%20设计.md)
   - 长期架构、统一 Runtime、安全边界和第一版原则的权威来源。
2. [科研 Project Agent 第一版实施计划](./当前有效/科研%20Project%20Agent%20第一版实施计划.md)
   - 当前阶段、串行任务队列、基线、退出条件和下一步工作的唯一执行来源；阶段 12 至阶段 15 是当前后续路线。
3. [科研工具与结构化索引契约](./当前有效/科研工具与结构化索引契约.md)
   - 五个第一批只读科研工具、结构化索引、Evidence 和预算的冻结契约。
4. [Agent 对比分析与后续改造建议](./当前有效/架构决策/Agent%20对比分析与后续改造建议.md)
   - 2026-07-14 架构决策依据：先收口统一单 Agent 主链，再扩多 Agent。
5. [MVP 后续优化与云沙箱选型](./当前有效/架构决策/MVP后续优化与云沙箱选型.md)
   - LLM Router、统一入口、界面冻结、普通问答与 Evidence 分层、工具自修复、事件触发 Reflection，以及云沙箱选型的架构决策。

发生冲突时，优先级为：

```text
安全与冻结契约
-> 通用 Agent Runtime 设计
-> 科研 Project Agent 第一版实施计划
-> 架构决策记录
-> 历史实施与评测记录
```

## 当前下一步

Worker 16 至 Worker 19 已完成第一轮体验与可靠性收口。Worker 20 至 Worker 23 已完成第二轮：结果语义与证据底座、受控 Final Synthesis、结果展示，以及固定编码任务闭环均已通过工程与真实用户旅程验收。

> Worker 20 至 Worker 23 的四阶段串行计划已全部完成。当前没有自动启动的后续开发 Worker；自由多 Agent、Pro 模式和外部检索扩展必须另行讨论、冻结权限与验收矩阵后才能启动。

Worker 20 的真实 API -> Broker -> E2B 旅程已覆盖 Java 成功、非零失败、取消和 DIRECT；Windows Broker 只额外继承经探针证明必要的 `SystemRoot`，不继承 PATH 或业务敏感环境。

Worker 21 的真实 API -> Broker -> E2B 旅程已覆盖 Java 成功、非零失败、中文偏好、恶意 stdout 数据边界、刷新与 API 重启恢复。最终答复只保留一个 assistant 和一个 canonical answer；读取恢复不调用模型，并发发布在数据库锁内收敛到首个权威结果。

Worker 22 的真实浏览器旅程已覆盖 DIRECT、E2B Java 成功、非零失败、等待确认、取消、Evidence 多状态、刷新与 API 重启恢复。执行结果、用户任务结果与回答依据分别展示；Plan 终态默认折叠，完整 stdout/stderr 和技术记录仍可展开；桌面和 390px 窄屏无横向溢出，三区高度算法与单输入入口不变。

Worker 23 已把上述能力固化为 [Project 编码闭环固定验收集](../docs/process/project-coding-acceptance.md)。真实旅程覆盖 DIRECT、版本化只读代码、Plan E2B 成功/失败、同一步 RepairContext 改参成功、Candidate 成功/失败验证、拒绝/接受、恰好一个新 ProjectVersion、Evidence 版本隔离、rollback/export、确认/取消、Broker 不可用降级、刷新/API 重启和桌面/窄屏。Project 顶层仍只有 `DIRECT / PLAN_EXECUTE`，Candidate 在显式接受前保持 `NOT_APPLIED`。

执行原则：Project 的 LLM Router 提出 `DIRECT / PLAN_EXECUTE`，Runtime 只校验 capability、权限、工具、沙箱确认与预算并执行；普通非 Project Chat 暂时保留 ReAct。每个阶段串行开发、独立审查、真实用户旅程验收后再进入下一阶段。

## 历史归档

- `历史归档/旧路线`：已被新设计取代的早期排期和协作方式。
- `历史归档/实施记录`：已完成阶段的过程记录，只用于追溯。
- `历史归档/评测与审查`：历史发布门、验收矩阵和审查报告。

归档表示“不再作为当前排期依据”，不表示内容可以删除。安全反例、测试命令、验收结论和历史风险仍可作为后续回归证据。
