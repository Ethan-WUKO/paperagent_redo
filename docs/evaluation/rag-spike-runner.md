# RAG Spike Runner

本文记录 RAG spike runner 的当前最小用法。

## Baseline runner

当前 baseline runner 位于 `yanban-knowledge/src/test/java/com/yanban/knowledge/eval/`，用于后续对照当前自研 RAG 和 LangChain4j RAG。

核心类型：

```text
BaselineRagRunner
BaselineSearchBackend
KnowledgeSearchServiceBaselineBackend
BaselineRagEvaluationResult
RagSpikeFixtureLoader
```

`KnowledgeSearchServiceBaselineBackend` 用于包装当前生产 RAG 检索接口：

```text
KnowledgeSearchService.search(query, userId, topK)
```

测试中的 `FixtureBackedBaselineSearchBackend` 只用于验证 fixture、case、指标和 JSON 输出格式，不代表生产 RAG 质量。

## 本地验证命令

```powershell
mvn -pl yanban-knowledge -am -Dtest=BaselineRagRunnerTest '-Dsurefire.failIfNoSpecifiedTests=false' test
```

该命令会验证：

1. `documents.json` 和 `cases.json` 可被解析。
2. fixture-backed baseline 可以跑完 10 个 case。
3. 结果可以写成 JSON。
4. `KnowledgeSearchServiceBaselineBackend` 能把当前 `KnowledgeSearchResult` 映射成统一 hit 格式。

## 输出格式

结果对象为 `BaselineRagEvaluationResult`，包含：

```text
runner
createdAt
summary
cases
```

summary 当前包含：

```text
totalCases
passedCases
failedCases
recallAt5
meanReciprocalRank
forbiddenHitCount
metadataPreservationRate
```

后续 #15 的 LangChain4j adapter-only runner 必须输出兼容结构，方便 #16 汇总对照报告。

## LangChain4j adapter-only runner

`LangChain4jAdapterRagRunner` 位于同一 test/eval 包中，只用于 spike，不进入生产链路。

当前实现：

```text
KnowledgeSearchService
  -> KnowledgeSearchServiceContentRetriever
  -> LangChain4j Content/TextSegment
  -> LangChain4jAdapterRagRunner
  -> BaselineRagEvaluationResult compatible JSON shape
```

该 runner 只验证 LangChain4j RAG adapter 层是否能保留 metadata 和统一结果结构，不做生产替换，也不重建 Elasticsearch index。

验证命令：

```powershell
mvn -pl yanban-knowledge -am '-Dtest=BaselineRagRunnerTest,LangChain4jAdapterRagRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```
