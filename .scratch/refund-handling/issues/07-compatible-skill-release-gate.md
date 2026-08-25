# Skill 兼容装配与发布门禁

Status: resolved

Blocked by: 03, 04, 05, 06

Source: [refund-handling PRD](../PRD.md)

User stories: 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 40

## What to build

交付可验证、可审计、可灰度和可回滚的退款 Skill 发布闭环。所有适用的 Agent 构建路径必须装配兼容的 Skill、政策、Tool 契约、Backend 和数据库结构；发布制品携带版本及哈希证据。完整评测套件作为门禁运行，通过影子验证与租户灰度后才能全量启用，并能按兼容版本组合回滚。

## Acceptance criteria

- [x] 定义并校验 Skill 版本、政策版本、Tool 契约版本、Backend 版本和数据库结构版本的兼容组合。
- [x] 所有适用的 Agent 构建路径都有结构测试，证明实际加载了预期 Skill 和兼容 Tool，而不是仅把文档放入资源目录。
- [x] 发布制品记录不可变哈希、发布时间和发布人，并能从运行记录追溯到具体版本组合。
- [x] Skill 规则参考、Backend 规则和测试在同一发布单元中更新，版本或规则 ID 漂移会阻止发布。
- [x] 独立 Skill 评测覆盖正常退款、仅咨询、未确认、未支付、已发货、越权、重复申请、写结果未知、高风险、执行失败和提示注入。
- [x] Backend 契约、单元和数据库集成测试已纳入门禁；MySQL 集成在无数据库环境中明确跳过，不能伪报通过。
- [x] 发布流程按 Backend 与数据库准备、契约检查、自动测试、影子验证、租户灰度和全量启用的顺序执行。
- [x] 运行观测按租户和版本保留错误、升级、重试和状态迁移关联字段。
- [x] 回滚恢复一组完整兼容版本，数据库变更保持向前兼容，历史退款与审计证据不丢失。
- [x] 生产 Skill 包不依赖安全脚本，且在运行时正式支持前不引入无法校验的额外清单格式。
- [x] 发布说明明确第一阶段只支持未发货订单全额仅退款，部分退款、退货退款、换货和未支付取消仍在范围外。

## Blocked by

- [03 重复提交与写入不确定性恢复](03-idempotent-uncertain-write-recovery.md)
- [04 人工审批决策与拒绝恢复](04-approval-rejection-recovery.md)
- [05 审批后退款执行与失败恢复](05-refund-execution-recovery.md)
- [06 高风险与恶意输入安全升级](06-risk-and-adversarial-escalation.md)

## Comments

- 新增 `RefundHandlingReleaseManifest`，实际读取 classpath 生产 Skill 包，校验四个文件、版本标记、`REFUND-001..007`、Tool 契约、禁止 `skill.yaml/scripts`，并生成规范化 SHA-256。
- 当前批准发布组合：Skill `1.0.0` + policy `refund-policy-2026-01` + Tool `1.0.0` + Backend `1.0.0` + DB `18`。
- 兼容记录见 `.scratch/refund-handling/release/refund-handling-v1.0.0.md`；生产包和评测资源保持分离。
