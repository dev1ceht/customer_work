# 人工审批决策与拒绝恢复

Status: resolved

Blocked by: 02

Source: [refund-handling PRD](../PRD.md)

User stories: 6, 9, 26

## What to build

交付人工审批后的业务决策路径。审核员可以将待审核申请批准为待执行，或拒绝并释放履约冻结、恢复申请前的订单状态。客户查询时能分别看到申请与审批状态；被拒申请不能由 Agent 自动重新创建，只能引导到人工申诉。

## Acceptance criteria

- [x] 待审核申请只能通过受授权的人工审批入口进入批准或拒绝状态。
- [x] 批准后状态进入 `APPROVED_PENDING_EXECUTION`，但不会被描述为已退款或已到账。
- [x] 拒绝后状态进入 `REJECTED`，并在一致性边界内释放履约冻结、恢复申请前的订单状态。
- [x] 审批接口校验合法前置状态，重复审批返回稳定结果，冲突审批不会覆盖已完成决策。
- [x] 退款进度查询分别返回申请状态和审批状态，不用审批结果代替资金执行结果。
- [x] Agent 对拒绝结果说明人工申诉渠道，不自动调用提交退款重新申请。
- [x] 审批审计记录包含审批人、时间、决策、理由、退款单号、政策版本和规则证据。
- [x] 数据库集成测试覆盖批准、拒绝、重复审批、并发冲突、拒绝恢复失败回滚和历史状态恢复。
- [x] Agent 行为测试证明批准、拒绝和审批中三种结果使用准确且不误导的用户措辞。

## Blocked by

- [02 经用户确认创建待审核退款](02-confirmed-refund-application.md)

## Comments

- 已实现 `approveDecisionOnly` / `denyDecisionOnly`、审批入口身份校验、冻结释放、订单状态恢复和结构化进度查询。
- 审批决策与申请/冻结/订单恢复在 MyBatis 事务边界内完成；重复或并发决策返回 `CONFLICT`。
- 定向回归：`RefundConfirmationFlowTest`、`AfterSalesToolsTest`、`PendingApprovalServiceTest`、`RefundConsultationBehaviorTest`；MySQL 集成用例在本地因数据库不可用自动跳过。
