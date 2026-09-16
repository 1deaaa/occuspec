# 检索质量与性能基准（docs/benchmark.md）

> 本文档记录系统关键路径的实测基准数据，供容量评估、回归对照与架构决策使用。
> 全部数据来自本机实测（`server/src/test/java/com/occuspec/RetrievalBenchmarkTest.java`
> 与本地服务压测），给出复现命令，不引用估算值。
>
> 环境：Windows 11 / JDK 21.0.10 / MySQL 8.4 / PostgreSQL 18.4 + pgvector 0.8.1 /
> Redis 8.10.1；Embedding 服务 SiliconFlow `Qwen/Qwen3-Embedding-8B`（dimensions=1024）。

## 1. 检索精确率：纯向量 vs 元数据过滤

### 1.1 方法

- 数据规模：条款 5933 条，向量 5933 条（全量入库，metadata 含
  `hazard_code / phase / check_class / appendix_type / standard_code`）。
- 相关性标注：以条款自身的 `hazard_code` 为标注（该字段由 GBZ 188 章节结构生成，
  非人工临时判断）。查询某危害因素时，命中条款的 `hazard_code` 与查询一致即视为相关。
- 指标：`P@K`（K=5），即 Top-5 结果中相关条款占比；对 10 个危害因素取平均。
- 对照：
  - `plain`：仅向量相似度检索，不带任何过滤；
  - `filtered`：向量检索 + `hazard_code` 过滤（系统在判定流程中的实际用法）。

### 1.2 结果

| 危害因素 | plain P@5 | filtered P@5 |
|---|---|---|
| 噪声（gbz188-7-1） | 0.600 | 1.000 |
| 游离二氧化硅粉尘（gbz188-6-1） | 0.600 | 1.000 |
| 铅及其化合物（gbz188-5-1） | 0.400 | 1.000 |
| 苯（gbz188-5-19） | 0.400 | 1.000 |
| 煤尘（gbz188-6-2） | 0.600 | 1.000 |
| 高温（gbz188-7-3） | 0.200 | 1.000 |
| 甲苯（gbz188-5-58） | 0.600 | 1.000 |
| 电工作业（gbz188-9-1） | 0.600 | 1.000 |
| 布鲁氏菌（gbz188-8-1） | 0.400 | 1.000 |
| 石棉粉尘（gbz188-6-3） | 0.400 | 1.000 |
| **平均** | **0.480** | **1.000** |

**结论**：元数据过滤把 Top-5 精确率从 0.480 提升到 1.000（+108.3%）。
纯向量检索在危害因素相近时（如各类粉尘、卤代烃）会跨危害串召回；
按 `hazard_code` 过滤后全部收敛到目标危害节，且检索结果可直接作为判定依据引用。

### 1.3 复现

```bash
# 需先完成向量入库（服务端 POST /api/v1/admin/rag/embed），并注入嵌入服务密钥
cd server
$env:EMBEDDING_API_KEY="<key>"
mvn test -Dtest=RetrievalBenchmarkTest
```

## 2. 向量入库吞吐

| 项目 | 实测值 |
|---|---|
| 条款总数 | 5933 |
| 分批大小 | 16 条/批 |
| 并发度 | 4（`Semaphore(4)`，受上游 QPS 与 token 双限流约束） |
| 全量入库耗时 | 约 35 分钟（5933 条） |
| 上游限额 | 2000 请求/分钟、1,000,000 token/分钟 |

并发度取 4 的原因：上游 Embedding 服务同时限制请求数与 token 数，
单批 16 条 × 平均 ~500 字符，4 路并发可稳定落在限额内；更高并发会被上游限流反而更慢。

## 3. 热点缓存

`HotCache` 为条款精确查询与元数据发现提供 cache-aside（一级进程内 + 二级 Redis），
并覆盖三类典型问题：

| 问题 | 处理方式 | 验证 |
|---|---|---|
| 穿透 | 空结果以 30s 短 TTL 空值占位 | `HotCacheTest.空结果以占位缓存防穿透` |
| 击穿 | 同 key 并发回源单飞，仅一个线程查库 | `HotCacheTest.并发回源单飞只查一次`（16 并发仅 1 次回源） |
| 雪崩 | TTL 附加 ±20% 随机抖动 | 代码路径 `HotCache.jitter` |

条款内容近乎静态，TTL 设 6 小时；元数据发现为全表聚合，TTL 设 30 分钟。

### 3.1 命中率与延迟实测

`CacheBenchmarkTest` 实测（一级缓存为进程级单例，测试内先清理再计量）：

| 场景 | 冷回源 | 热命中 | 命中率 |
|---|---|---|---|
| 条款精确查询（200 次） | 5 条共 8ms，平均 1.60ms | 200 次共 15ms，平均 0.010ms，P95=0ms | 100% |
| 模拟热点键（20 键 × 200 次） | 20 次共 312ms，平均 15.10ms | 200 次共 0ms，平均 0.000ms，P95=0ms | 90.9% |

热命中平均耗时较冷回源低约 **2 个数量级**（1.60ms → 0.010ms）。
命中率未达 100% 时，缺口全部来自冷启动阶段的首轮回源，符合预期。

## 4. 索引与查询计划

### 4.1 向量检索

向量检索原始 SQL 为 `ORDER BY metadata_hits DESC, embedding <-> ? LIMIT k`，
其中 `metadata_hits` 是计算表达式。**该写法使规划器无法使用 HNSW 索引**，
实测退化为全表顺序扫描（5933 行，2.8ms，且随数据量线性增长）。

改为两段式查询后（内层 `ORDER BY 距离 LIMIT 候选数`，外层按元数据加权重排）：

| 查询 | 计划 | 耗时 |
|---|---|---|
| 原始写法（加权在 ORDER BY 首列） | Seq Scan | 2.83ms |
| 强制走 HNSW（`enable_seqscan=off`） | Index Scan (hnsw) | 0.85ms |

需要说明的实测事实：当前数据规模（5933 行）下，规划器即使面对两段式写法仍选择
Seq Scan（约 16ms），因为 pgvector 的成本模型在该规模判断顺序扫描更省。
HNSW 的收益随数据量增长而显现，两段式改写保证届时无需改代码即可用上索引。

### 4.2 元数据过滤

`V2__vector_index_tuning.sql` 为过滤维度建表达式索引（`metadata->>'xxx'`）：

| 查询 | 索引前 | 索引后 |
|---|---|---|
| `hazard_code = 'gbz188-7-1'` | Seq Scan，Rows Removed by Filter 5912，2.8ms | Bitmap Index Scan，0.096ms |
| `hazard_code + phase` 组合 | Seq Scan | Index Scan，0.012ms |

提升约 **1–2 个数量级**。

### 4.3 关系库查询

MySQL 侧实测（`EXPLAIN`），各高频查询均走索引：

| 查询 | 计划 | 关键索引 |
|---|---|---|
| 条款按危害因素 + 排除 DOC 标记 | `ref`，rows=21 | `idx_hazard` |
| 条款按（标准号，条款编号）精确取 | `const`，rows=1 | `uk_std_clause` |
| 规则按启用 + 危害因素 | `range`，rows=2 | `idx_enabled_hazard` |
| 体检明细按 exam_id | `ref`，rows=2 | `idx_exam_item` |
| 对话消息按 session_id + 时间 | `ref`，rows=2 | `idx_session_time` |
| 条款按内容哈希去重 | `ref`，rows=1，Using index | `idx_content_hash` |

唯一未走索引的是标题/正文关键词模糊查询（`LIKE '%kw%'`），全表扫描 5682 行。
该路径仅用于条款浏览页的人工检索，不在判定链路上，暂不引入全文索引。

## 5. 并发与一致性

| 项目 | 实现 | 验证 |
|---|---|---|
| 批量任务抢占 | `UPDATE ... WHERE status='PENDING'` 条件更新按影响行数判断归属 | `BatchTaskMapper.claim` |
| 批量判定并发 | 经 `assessExecutor`（判定专用池，与调度隔离）并发执行 | `BatchTaskService.run` |
| 分布式锁释放 | Lua 脚本原子比对 token 后删除，防误删他人锁 | `DistributedLock.tryRun` |
| 导入事务边界 | 经自身代理调用使 `@Transactional` 生效 | `TransactionRollbackTest` |
| 判定 Agent 轮次上限 | 模型自主循环，硬性上限 8 轮防无效循环 | `AssessAgentService.MAX_ROUNDS` |

### 5.1 串行 vs 并发实测

`ConcurrencyBenchmarkTest` 用可控延迟模拟 IO 密集任务（40 个任务、单个 20ms、并发度 4）：

| 模式 | 总耗时 | 吞吐 | 峰值并发 |
|---|---|---|---|
| 串行 | 1239ms | 32.3 任务/秒 | 1 |
| 并发（CompletableFuture + Semaphore(4)） | 310ms | 129.0 任务/秒 | 4 |

**加速比 4.00x**，与并发度上限一致。限流有效性单独验证：
32 线程提交 100 个任务，`Semaphore(4)` 下峰值并发严格为 4，未越界。

真实场景加速比更高：单条判定含模型调用（40–140s），20 条批量任务串行需 15–45 分钟，
并发 4 路后降至约 1/4。

## 6. 判定链路耗时构成

判定已由固定四步改为 Agent 自主编排（见 `docs/rag-design.md`）。真实上游实测：

| 场景 | 轮次 | 工具调用 | 引用条款 | 总 tokens | 备注 |
|---|---|---|---|---|---|
| 噪声（听阈 45 dB） | 7 | 13 | 61 | 113,552 | 自主跨标准检索 GBZ49/GBZT325，结论上调为疑似职业病 |
| 苯（白细胞 3.2） | 6 | 11 | 72 | 118,433 | 自主检索 GBZ68/GBZT325/GBZT260 |

耗时构成：

| 阶段 | 耗时 |
|---|---|
| 危害路由（数据库直查，走索引） | 数十毫秒 |
| 每轮向量检索（含嵌入调用） | 0.3–3 s |
| 规则匹配（下限计算） | 亚毫秒 |
| 模型推理（`reasoning_effort=xhigh`，6–7 轮） | 3–8 分钟 |
| 落库（评估+证据+推荐，同一事务） | 数十毫秒 |

模型推理轮次是绝对瓶颈，且随 Agent 自主检索步数增加而增长。
生产部署建议：判定走 SSE 流式（已有 `/assessments/stream`），
把工具调用与推理过程实时推送；或按需下调 `reasoning-effort` 换取速度。

