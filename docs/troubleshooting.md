# 排障记录（docs/troubleshooting.md）

> 记录开发过程中真实发生过、且**不看日志很难猜出来**的问题。
> 每条都写清症状、排查过程与根因，目的是下次遇到同类现象时能直接对上号。
> 阶段与环境信息随每条记录标注，便于判断是否仍然适用。

## 目录

1. [SSE 经过 Next.js rewrite 代理后失去流式](#1-sse-经过-nextjs-rewrite-代理后失去流式)
2. [雪花 ID 超出 JS 安全整数导致"资源不存在"](#2-雪花-id-超出-js-安全整数导致资源不存在)
3. [Sa-Token 会话未持久化：重启后端即掉登录态](#3-sa-token-会话未持久化重启后端即掉登录态)
4. [`@Transactional` 因自调用失效](#4-transactional-因自调用失效)
5. [向量检索用不上 HNSW 索引](#5-向量检索用不上-hnsw-索引)
6. [pgvector 维数上限导致的存储类型选择](#6-pgvector-维数上限导致的存储类型选择)
7. [老标准纯文本章号导致切分只剩 1 条](#7-老标准纯文本章号导致切分只剩-1-条)
8. [危害因素编码双轨制导致过滤静默失效](#8-危害因素编码双轨制导致过滤静默失效)

---

## 1. SSE 经过 Next.js rewrite 代理后失去流式

**症状**：后端直连（`:8080`）测试时事件是逐条推送的；
经过前端域名（`:3000/backend/...`）访问时，推理内容与正文**一次性全部出现**，
"检索中…"的流式观感完全消失。前端换任何渲染库都没用。

**排查过程**：

1. 先怀疑前端读取逻辑 —— 直连后端用 PowerShell 逐行读事件流，确认后端是真流式
   （事件在 85ms 开始到达，之后每 ~30ms 一条）。
2. 在浏览器控制台里直接 `fetch("/backend/chat/stream")` 并统计 `reader.read()` 的分块：

   ```js
   // 走 Next.js 代理（修复前）
   { chunkCount: 1, first: [{ t: 44169, bytes: 28509 }] }
   ```

   整个 28KB 响应作为**单个 chunk 在 44 秒时到达** —— 问题定位在代理层，不在前端也不在后端。

3. 查看 `next.config.ts`，发现 `/backend/*` 是通过 `rewrites` 转发到后端的。

**根因**：Next.js 的 `rewrites`（以及 Middleware/Proxy 的 rewrite）会把上游响应
**完整缓冲后一次性回传**，不保持 chunked 透传。SSE 依赖的增量到达因此被抹平。

**修复**：改用 Route Handler 把上游响应体当 `ReadableStream` 直接透传：

- 删除 `next.config.ts` 里的 `rewrites`
- 新增 `client/src/app/backend/[...path]/route.ts`，`fetch` 上游后返回 `new Response(upstream.body)`
- 响应头加 `cache-control: no-cache, no-transform` 与 `x-accel-buffering: no`，关闭中间层缓冲

**修复后实测**：

| | 首个 chunk | chunk 数 |
|---|---|---|
| rewrite 代理 | 44169ms | 1（28KB 一次性） |
| Route Handler | **62ms** | 逐条，每几十~几百 ms |

浏览器 UI 复核：消息正文长度逐秒递增（968 → 4177 字符），确认是流式渲染。

**经验**：涉及 SSE/WebSocket/大文件下载这类需要"边生成边传"的接口，
不要用 `rewrites` 图省事；用 Route Handler 透传流。判断方法很直接——
数 `reader.read()` 返回了几次。

---

## 2. 雪花 ID 超出 JS 安全整数导致"资源不存在"

**症状**：对话第一句正常，**发第二句必定报"资源不存在"**（会话不存在）。
手动到数据库查，会话确实存在。

**排查过程**：

1. 前端把 `sessionId` 存成 `number` 再回传。打印前后端的值：

   ```
   后端返回：2100132345519558658
   JS 收到： 2100132345519558700   ← 末位被改写
   ```

2. 对照 JS 的 `Number.MAX_SAFE_INTEGER = 9007199254740991`（16 位），
   而雪花 ID 是 19 位（约 2.1e18），**超出安全整数范围**。
   `JSON.parse` 对这种超范围整数会静默丢失精度，不报错。

3. 第一句用后端返回的 ID（已失真）去查 → 查不到 → 第二句失败。

**根因**：后端主键为 MyBatis-Plus 雪花算法生成的 19 位 `Long`，
序列化为 JSON number 后在前端失真。影响所有 ID 字段：
`sessionId` / `examId` / `assessmentId` / `uploadId` / `personId`。

**为什么不一刀切把 Long 全转字符串**：同一个 `Long` 类型既承载主键，
也承载 token 数、耗时等计数器。若全部转字符串，前端
`usage.total > 0` 这类判断会因 JS 字符串真值语义而失真（`"0"` 为真）。

**修复**：`config/SafeLongSerializer.java`，**按数值范围判定**——
超出安全整数才输出字符串，计数器保持数字：

```java
if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) {
    gen.writeString(value.toString());
} else {
    gen.writeNumber(value);
}
```

同时注册 `Long.class` 与 `Long.TYPE`（原始类型也要，否则 `long` 字段会漏），
并在前端引入 `SnowflakeId = string` 类型，比较一律用字符串相等。

**为什么按范围判定而不是逐个字段加注解**：字段太多且会新增；
按范围判定对对象字段和 Map 值都生效（SSE 事件载荷里的 `sessionId` 是手工拼的 Map，
加注解会漏），无需人工维护。

---

## 3. Sa-Token 会话未持久化：重启后端即掉登录态

**症状**：后端重启后，浏览器里的旧 token 立即失效，
所有接口报未登录；用户不知情，只看到请求失败。

**排查过程**：

1. 排查未登录时为什么返回 500 而不是 401 —— 这是另一个问题（见下）。
2. 查 `application.yml`，Sa-Token 未配置 Redis 持久化；
   查 `pom.xml` 注释，明确写着"本地 Redis 3.x 不支持 1.46 的 `SET KEEPTTL`，先用内存会话"。
3. 实测确认：拆开 `sa-token-redis-template-1.46.0.jar` 看字节码，
   确有 `setStringAndKeepTTL`；本机 Redis 版本 `redis_version:3.0.504`（2016 年），
   执行 `SET k v EX 60 KEEPTTL` 返回 `ERR syntax error`（该参数 Redis 6.0 才引入）。

**根因**：会话存内存，进程重启即清空。属于**环境限制（Redis 版本过旧）**，非代码缺陷。

**当时的缓解**（现已不再需要）：
- 后端：未登录改返回 **401 + JSON**（见下方"顺带修掉的"），而不是 500；
- 前端：识别 401 时清除本地 token 并跳登录页，而不是静默失败；
- 文档：注明升级路径（Redis ≥ 6.0 后换回 `sa-token-redis-jackson`）。

**最终修复**：升级到 Redis 8（Docker Compose 管理，见 `docker-compose.yml`），
并在 `pom.xml` 重新引入 `sa-token-redis-jackson`，会话落 Redis。
验证：登录拿 token → 重启后端 → **同一个 token 仍返回 200**；
Redis 中可见 `satoken:login:token:*`、`satoken:login:session:*` 等键。
（保留上述 401 处理：它不是权宜之计，而是正确的鉴权失败语义，仍然需要。）

**顺带修掉的**：未登录时返回 500 而非 401。原因是 SSE 请求带
`Accept: text/event-stream`，鉴权失败返回 JSON 时内容协商失败，
抛出 `HttpMediaTypeNotAcceptableException`，把真实的"未登录"掩盖成"服务器错误"。
改为 `ResponseEntity` 显式 `contentType(APPLICATION_JSON)` + 401。

**经验**：
1. 项目实际用到的 Redis 命令（`SET EX` / `SETNX` / `SET NX EX` / `EXPIRE` /
   `INCR` / `EVAL` / `PING`）在 3.0.504 上就能用，**只有 Sa-Token 的会话持久化**
   需要 `KEEPTTL`（Redis 6.0+）。所以"项目能跑"不代表"依赖版本够新"，
   要按**具体命令**判断而不是整块判断。
2. 遇到环境版本限制时，"降级 + 文档注明升级路径"是可接受的过渡；
   但一旦环境能升级，就该把降级项真的换回来，而不是长期留在注释里。
   本次升级后 Redis 相关代码零改动，只有依赖与部署方式变了。

---

## 4. `@Transactional` 因自调用失效

**症状**：标准导入单文件的 `@Transactional` 看起来加了，但出错时已写入的条款不回滚。

**排查过程**：`importAll()` 内部直接 `this.importFile(file)` 调用。
Spring AOP 基于代理，**同一个类内部的方法调用不经过代理**，
`@Transactional` 完全没生效。

**修复**：注入自身代理（`@Lazy` 构造器注入）并通过 `self.importFile(...)` 调用，
使事务注解生效。另一个可选方案是 `AopContext.currentProxy()`，但要求开启 `exposeProxy`。

**顺带补充了验证**：新增 `TransactionRollbackTest`，用事务管理器手动回滚，
断言数据确实不落库（此前项目里没有任何回滚验证）。

**经验**：凡是"类内方法互相调用 + 期望切面（事务/缓存/审计）生效"的地方都要警惕。
本项目还抽了 `HazardRegistry`，把职责从导入服务里拆出去，
既解决自调用，也让状态不再依赖导入流程的生命周期。

---

## 5. 向量检索用不上 HNSW 索引

**症状**：条款向量表已建 HNSW 索引，但 `EXPLAIN ANALYZE` 显示走的是
**全表 Seq Scan**，索引形同虚设。

**排查过程**：

1. 确认索引存在：`idx_clause_emb_hnsw USING hnsw (embedding halfvec_l2_ops)`。
2. 强制 `SET enable_seqscan=off` 后能走索引（2.83ms → 0.85ms），说明索引本身可用，
   是规划器**主动不选**。
3. 对比查询写法，发现排序是：

   ```sql
   ORDER BY metadata_hits DESC, embedding <-> ? LIMIT k
   ```

   `metadata_hits` 是个计算表达式（元数据命中维度数），被放在 `ORDER BY` 首列。
   主排序键不是距离，HNSW 无法用于这种排序。

**修复**：改成**两段式查询**——
内层只按距离排序（可命中 HNSW），外层再做元数据加权重排：

```sql
SELECT ... FROM (
  SELECT ..., embedding <-> ?::halfvec AS distance, <命中表达式> AS metadata_hits
  FROM clause_embedding WHERE <过滤> ORDER BY embedding <-> ?::halfvec LIMIT <候选数>
) AS candidates
ORDER BY metadata_hits DESC, distance ASC LIMIT <topK>
```

另外为过滤维度建了**表达式索引**（`metadata->>'hazard_code'` 等），
过滤不再回表逐行求值：

| 查询 | 索引前 | 索引后 |
|---|---|---|
| `hazard_code = '...'` | Seq Scan，Rows Removed 5912，2.8ms | Bitmap Index Scan，0.096ms |
| `hazard_code + phase` | Seq Scan | Index Scan，0.012ms |

**如实记录一个反直觉的实测结果**：当前数据规模（5933 行）下，
规划器即使面对两段式写法仍选择 Seq Scan（约 16ms），
因为 pgvector 的成本模型在该规模判断顺序扫描更划算。
**HNSW 的收益要等数据量增长才显现**，两段式改写保证届时不必再动代码。

**经验**：建了索引不等于用上索引。`EXPLAIN` 要真看；
`ORDER BY` 里混入非距离列是向量检索的常见陷阱。

---

## 6. pgvector 维数上限导致的存储类型选择

**症状**：用 `Qwen3-Embedding-8B` 默认的 4096 维建 `vector(4096)` 并建 HNSW 索引，
报 `column cannot have more than 2000 dimensions for hnsw index`。

**排查过程**：pgvector 0.8.1 的 `vector` 类型 HNSW 索引上限为 **2000 维**。
超上限无法建索引，检索会退化为暴力扫描。

**修复**：改用 `halfvec(1024)`：
- `halfvec`（半精度）支持更高维度的索引；
- 通过兼容接口传 `dimensions=1024` 让模型输出 1024 维。
  实测该参数生效，检索质量满足条款召回需求（见 `benchmark.md` §1）。

**经验**：选 embedding 维度时先确认向量库的索引维数上限，
不要等到建索引才踩坑。

---

## 7. 老标准纯文本章号导致切分只剩 1 条

**症状**：GBZ 49（职业性噪声聋诊断标准）、GBZ 70 等老标准入库后，
每个文件只切出 1 条条款，明显不对。

**排查过程**：切分器最初依赖 Markdown 标题（`#`/`-` 前缀）识别条款编号。
但老标准正文里章号是**纯文本**（如"3 诊断原则"独占一行，没有 `#`/`-`），
全部内容因此被当成一块。

**修复**：新增 `PLAIN_CLAUSE` 模式与 `isPlainClause` 判定，
兼容无前缀的纯文本条款编号。实测 GBZ49 从 1 → 7 条，GBZ70 从 1 → 9 条。

**经验**：国标 Markdown 的格式并不统一（新旧标准、不同来源混排），
切分键必须基于**条款编号文本**而非标题层级。这也是 `docs/rag-design.md` §1 的核心结论。

---

## 8. 危害因素编码双轨制导致过滤静默失效

**症状**：按危害因素过滤条款时"看起来没生效"，返回结果里混着其他危害因素的条款。

**排查过程**：

1. 检查数据发现**两套编码并存**：
   - 危害因素目录与条款用规范编码 `gbz188-<章>-<节>`（如 `gbz188-7-1` 噪声）；
   - 早期种子规则与既有体检记录用简写短码（`noise`、`lead`）。
2. 两套编码不通 → 按危害因素过滤时匹配不到，**静默退化为全库检索**（不报错，最危险）。

**修复**：V8 迁移把历史短码统一为规范编码，并清理冗余短码危害条目；
前端新增数据从 `hazards` 表下拉直接取规范编码，不再产生短码。

量化验证（同一查询）：

| | 返回结果 |
|---|---|
| 无过滤 | 混入 6.4 / 6.7 / 附录 C 等他节条款 |
| 带 `hazard=gbz188-6-1` 过滤 | 精确锁定 6.1 节 |

**经验**：编码类的"软失效"最危险——不报错但结果错。
凡是过滤/匹配逻辑，都要用**能反证的测试**验证（比如构造一个必然混入的查询）。

---

## 附：排查这类问题的通用手法

1. **分层定位**：前端 / 代理 / 后端逐层验证，别在猜测的层上打转。
   例如 SSE 问题，先用 `reader.read()` 的块数判定是传输层还是渲染层。
2. **数一数，别只看结果**：事件的到达时间、chunk 数量、SQL 的查询次数与扫描行数，
   这些数字比"看起来对不对"可靠得多。
3. **强制手段反证**：`SET enable_seqscan=off` 能说明"索引可用但没被选中"，
   与"索引根本建错了"是两回事。
4. **超范围/精度类问题先算边界**：JS 安全整数上限、pgvector 维数上限这类硬约束，
   先核对数值落在哪里，比读代码快。
