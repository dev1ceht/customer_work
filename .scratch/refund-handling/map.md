# refund-handling 实施地图

- [PRD](PRD.md)
- [01 退款资格咨询安全链路](issues/01-safe-refund-eligibility-consultation.md) — resolved：结构化只读 Tool、Backend 政策裁决、标准 Skill 包与行为/契约测试。
- [02 经用户确认创建待审核退款](issues/02-confirmed-refund-application.md) — resolved：身份/确认令牌、金额重算、事务创建和履约冻结。
- [03 幂等与不确定写恢复](issues/03-idempotent-uncertain-write-recovery.md) — resolved：稳定幂等键、未知结果查询、同键安全重试和只读重试。
- [04 审批拒绝与恢复](issues/04-approval-rejection-recovery.md) — resolved：人工批准/拒绝、冻结释放和订单状态恢复。
- [05 退款执行恢复](issues/05-refund-execution-recovery.md) — resolved：支付执行租约、执行凭证、失败冻结和未知结果对账。
- [06 风险与对抗升级](issues/06-risk-and-adversarial-escalation.md) — resolved：高风险规则、人工升级、提示注入防护和日志脱敏。
- [07 兼容 Skill 发布门禁](issues/07-compatible-skill-release-gate.md) — resolved：版本组合、实际资源校验、哈希发布记录和回滚说明。
