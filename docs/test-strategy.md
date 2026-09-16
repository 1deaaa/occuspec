# 测试策略（docs/test-strategy.md）

> 本文档说明本项目的测试分层与运行方式，避免"测试是否允许联网"这类问题反复讨论。

## 1. 分层原则

| 层 | 是否联网 | 运行时机 | 例子 |
|---|---|---|---|
| 单元测试 | 否 | 每次改动、批量回归 | `RuleEvaluatorTest`、`ConclusionPolicyTest`、`ClauseSplitterTest` |
| 集成测试（离线） | 否（仅本地 MySQL/PG/Redis） | 每次改动、批量回归 | `ApiFlowTest`、`TransactionRollbackTest`、`HotCacheTest`、`FieldMappingServiceTest`、`CacheBenchmarkTest` |
| 功能级探索测试 | 是（真实模型/嵌入服务） | 人工按需，不纳入批量回归 | `AgentLiveProbeTest`、`RetrievalBenchmarkTest`、`EmbedSmokeTest#小批量入库` |
| 重型运维脚本 | 是 | 首次部署或重建时手动 | `EmbedSmokeTest#全量入库`（默认 `@Disabled`） |

核心区分是**运行方式**而非"能不能联网"：

- **批量回归**一次启动几十个测试，若每个都打上游，会互相挤占额度、耗时不可控、结果受上游抖动影响。
  这类测试必须离线且确定。
- **功能级探索测试**是开发某个功能时的一次性验证，例如"Agent 是否真的会自主选过滤维度"。
  这类结论用替身无法证明，必须打真实上游；按需运行、人工判读即可。

## 2. 运行方式

```bash
# 批量回归（默认，离线，不需要密钥）
cd server
mvn test

# 联网功能测试（需外网与模型密钥）
mvn test -Plive

# 单个功能级测试
mvn test -Dtest=AgentLiveProbeTest -DfailIfNoTests=false
# 运行前需注入密钥：
#   $env:LLM_API_KEY="<key>"; $env:EMBEDDING_API_KEY="<key>"
```

标记方式：联网测试类加 `@Tag("live")`。`pom.xml` 默认 `<excludedGroups>live</excludedGroups>`，
`-Plive` profile 覆盖该配置、放行全部测试。

## 3. 为什么不做全量向量入库的自动化

`EmbedSmokeTest#全量入库` 约 35 分钟、消耗大量上游额度，且会重复写入已有向量。
因此默认 `@Disabled`，重建向量请用运维接口：`POST /api/v1/admin/rag/embed`。

## 4. 新增测试的归属判断

- 只验证纯逻辑（含规则表达式、切分、映射）→ 单元测试，无 Spring 上下文。
- 需要数据库但不需要模型 → 集成测试（离线），可进批量回归。
- 结论依赖真实模型行为（Agent 选工具、抽取质量、检索精确率）→ 加 `@Tag("live")`，
  在类注释里写清"为什么必须打上游"，并给出手动运行命令。
