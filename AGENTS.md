# AGENTS.md（工程协作约定）

> 本文件约束所有参与本仓库的 Agent 与开发者。术语定义见 `CONTEXT.md`，
> RAG 切分与元数据规范见 `docs/rag-design.md`。

## 1. 分层约束

- `controller` 只做参数校验与 DTO 装配，禁止写业务逻辑。
- `service` 承载业务流程与事务边界，禁止直接拼接 SQL。
- `mapper`/`repository` 只做数据访问，复杂 SQL 放 XML。
- `rag` 包只做检索与向量存取，判定规则只允许出现在 `rule` 包。
- `llm` 调用必须经 `LlmGateway` 深模块，小接口 `complete(prompt) → 文本 + 用量`，
  隐藏超时、重试、降级细节；失败不得抛穿到接口层。

## 2. 通用规则

- 所有数据库变更写 Flyway 迁移脚本（`server/src/main/resources/db/migration/`），禁止手工改库。
- 所有外部耗时调用必须有超时、重试、降级三件套。
- 单元测试不调用真实模型、不联网；集成测试用内存替身。
- 新增复用逻辑先查 `common`/`utils` 包，不新增重复工具类。
- 提交信息使用中文，`feat:/fix:/chore:/docs:/test:` 前缀；禁止推送与修改远程配置。

## 3. 接口约定

- 前缀 `/api/v1`，资源用复数名词；列表返回 `{data, pagination}`；
  错误体统一 `{code, message, details}`；写操作支持 `Idempotency-Key` 幂等头。
- 前端所有用户可见文本走 `next-intl`（`zh-CN` 完整，`en-US` 后补），禁止硬编码中文。

## 4. 医疗边界（必须写入代码与文案）

- 系统为辅助判定工具，结论为建议性质，必须经主检医师复核签字后生效。
- 界面与报告显著标注"需主检医师复核"；不得出现确诊类表述。
- 机构自加建议必须带 `is_extended=true` 并与标准内推荐分开展示。
