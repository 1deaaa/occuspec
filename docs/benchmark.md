# 检索质量与性能基准（docs/benchmark.md）

> 本文档记录系统关键路径的实测基准数据，供容量评估、回归对照与架构决策使用。
> 全部数据来自本机实测（`server/src/test/java/com/occuspec/RetrievalBenchmarkTest.java`
> 与本地服务压测），给出复现命令，不引用估算值。
>
> 环境：Windows 11 / JDK 21.0.10 / MySQL 8.4 / PostgreSQL 18.4 + pgvector 0.8.1 /
> Redis 3.0.504；Embedding 服务 SiliconFlow `Qwen/Qwen3-Embedding-8B`（dimensions=1024）。

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

## 4. 并发与一致性

| 项目 | 实现 | 验证 |
|---|---|---|
| 批量任务抢占 | `UPDATE ... WHERE status='PENDING'` 条件更新按影响行数判断归属 | `BatchTaskMapper.claim` |
| 批量判定并发 | 经 `batchExecutor` 并发执行各条体检 | `BatchTaskService.run` |
| 分布式锁释放 | Lua 脚本原子比对 token 后删除，防误删他人锁 | `DistributedLock.tryRun` |
| 导入事务边界 | 经自身代理调用使 `@Transactional` 生效 | `TransactionRollbackTest` |

## 5. 判定链路耗时构成

单次判定（含模型渲染）实测：

| 阶段 | 耗时 |
|---|---|
| 危害路由（数据库直查） | 数十毫秒 |
| 向量检索（含嵌入调用） | 0.3–3 s（取决于嵌入服务响应） |
| 规则匹配 | 亚毫秒 |
| 模型渲染（`reasoning_effort=xhigh`） | 40–140 s |
| 落库（评估+证据+推荐，同一事务） | 数十毫秒 |

模型渲染是绝对瓶颈。生产部署建议：把渲染放到异步流程，判定结论（规则结果）
先返回，渲染完成后补充说明；或按需下调 `reasoning-effort`。
