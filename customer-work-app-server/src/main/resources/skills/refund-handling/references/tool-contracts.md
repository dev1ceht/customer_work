# 退款 Tool 契约

<!-- version: 1.0.0 -->

## `queryOrder(orderId)`

只读。返回结构化订单事实。订单号只能来自用户当前请求，不得把它解释成用户身份。

```json
{
  "queryStatus": "FOUND",
  "orderId": "20260613003",
  "paymentStatus": "PAID",
  "fulfillmentStatus": "NOT_SHIPPED",
  "productSummary": "运动防汗蓝牙耳机",
  "orderAmount": 199.00
}
```

## `checkRefundEligibility(orderId)`

只读。只接受 `orderId`；不接受 `withinSevenDays`、退款金额、支付状态、履约状态或资格标记等 Agent 可控参数。

```json
{
  "decision": "ELIGIBLE",
  "reasonCodes": ["ORDER_PAID", "NOT_SHIPPED"],
  "refundableAmount": 199.00,
  "riskLevel": "NORMAL",
  "policyVersion": "refund-policy-2026-01",
  "ruleIds": ["REFUND-001", "REFUND-002"]
}
```

只有 `ELIGIBLE` 可以返回非空 `refundableAmount`。已有活跃退款申请时返回 `HUMAN_REVIEW`，首期不计算剩余部分。

当金额命中 `REFUND-006` 或原因文本命中 `REFUND-007` 时，Backend 返回 `HUMAN_REVIEW` 和
`riskLevel=HIGH`；不得返回确认令牌或可自动执行的金额。Agent 应调用 `transferToHuman`，不能继续准备、提交、审批或执行。

## `prepareRefund(orderId, reasonCode, reasonText)`

只读准备。Backend 使用当前可信身份校验订单归属、重新计算资格和全额可退金额，并签发绑定当前决策的短期令牌。

- `reasonCode` 只能是 `CHANGED_MIND`、`ORDERED_BY_MISTAKE`、`DUPLICATE_ORDER`、`ORDER_INFO_ERROR`、`OTHER`。
- `reasonText` 最多 500 个字符，仅作不可信人工审核说明。
- `READY` 结果必须包含订单、商品、全部可退金额、原因、政策版本、规则 ID、`confirmationToken`、履约冻结和人工审批提示。
- 其他状态不得携带可提交令牌；订单不存在/不可见、身份缺失或归属不匹配均转人工。

示例：

```json
{
  "status": "READY",
  "orderId": "20260613003",
  "productName": "运动防汗蓝牙耳机",
  "refundableAmount": 199.00,
  "reasonCode": "CHANGED_MIND",
  "riskLevel": "NORMAL",
  "policyVersion": "refund-policy-2026-01",
  "ruleIds": ["REFUND-001", "REFUND-002"],
  "confirmationToken": "opaque-server-token",
  "fulfillmentFreezeRequired": true,
  "manualApprovalRequired": true
}
```

## `submitRefund(orderId, reasonCode, reasonText, confirmationToken)`

写入合同。只接受订单标识、批准原因码、受限原因文本和不透明确认令牌；**不接受退款金额、tenantId、userId、资格标志或自动审批字段**。

提交时 Backend 必须重新校验租户、已验证用户、订单归属、资格、全额可退金额、政策版本、令牌有效期和活跃申请幂等键。

成功结果示例：

```json
{
  "status": "CREATED",
  "orderId": "20260613003",
  "refundNo": "RF-...",
  "approvalId": "AP-...",
  "applicationStatus": "PENDING_REVIEW",
  "refundableAmount": 199.00,
  "riskLevel": "NORMAL",
  "policyVersion": "refund-policy-2026-01",
  "ruleIds": ["REFUND-001", "REFUND-002"],
  "idempotencyKey": "REFUND:FULL:default:20260613003"
}
```

`CREATED` 只表示退款申请、人工审批记录和履约冻结已在同一事务中创建；不表示已批准、已执行或已到账。
`EXISTING` 为重复提交的幂等结果，必须返回原 `refundNo`；只有带 `refundNo` 的 `CREATED` 或 `EXISTING` 才能被解释为申请已受理。
无确认、令牌过期/篡改、金额变化、身份失效或归属不匹配均返回 `HUMAN_REVIEW`，不得重试绕过。

## `queryRefundApplication(orderId)`

只读结果核对。Backend 按租户和订单生成稳定的全额退款业务幂等键，Agent 不提供幂等键，也不能修改它。

- `EXISTING`：返回原 `refundNo`、审批单号和当前 `applicationStatus`；不得再次写入。
- `NOT_FOUND`：明确没有活跃申请；仅允许使用同一订单再次调用 `submitRefund` 一次。
- `UNKNOWN`：查询本身也无法确认；转人工，不得自动写入。

`submitRefund` 返回 `UNKNOWN` 时必须先调用本工具。写入超时不能直接重试；只有本工具明确返回 `NOT_FOUND` 才能考虑一次同键重试。若返回 `EXISTING` 且 `applicationStatus=REJECTED`，这是历史拒绝结果，不是可以再次提交的许可。

## `queryRefundProgress(orderId)`

只读状态快照。Backend 返回申请、人工审批、资金执行三条独立状态链；Agent 必须按字段解释，不能把人工批准或申请创建说成到账。

```json
{
  "queryStatus": "FOUND",
  "orderId": "20260613003",
  "refundNo": "RF-...",
  "applicationStatus": "APPROVED_PENDING_EXECUTION",
  "approvalStatus": "APPROVED",
  "executionStatus": "NOT_APPLICABLE",
  "policyVersion": "refund-policy-2026-01",
  "ruleIds": ["REFUND-001", "REFUND-002"]
}
```

- `PENDING_REVIEW/PENDING`：申请已创建，等待人工审核。
- `APPROVED_PENDING_EXECUTION/APPROVED`：人工审核已通过，等待执行；不能说已打款或已到账。
- `REJECTED/DENIED`：申请被人工拒绝；不得自动再次调用 `submitRefund`，应引导人工申诉或继续人工处理。
- `EXECUTING`：资金执行处理中，不重复提交或执行。
- `EXECUTED`：支付渠道已明确受理并返回执行凭证；这不等于银行账户已到账，不能承诺具体到账时效。
- `EXECUTION_FAILED/EXECUTE_FAILED`：执行失败，履约冻结保持；受控恢复只能使用原退款申请、原审批和原幂等键，不创建新申请。
- `queryStatus=UNKNOWN`：状态无法确认，转人工，不猜测结果。

## 支付执行与恢复

支付执行不是 Agent Tool，只能由受控的支付执行服务调用。执行服务必须先确认人工审批状态为 `APPROVED`，再取得执行租约，并使用退款申请上的稳定 `idempotencyKey` 调用支付渠道。

- 渠道明确返回 `ACCEPTED`：原申请进入 `EXECUTED`，记录渠道凭证，订单进入已退款状态并释放冻结；用户措辞只能是“已提交支付渠道处理”。
- 渠道明确失败：原申请进入 `EXECUTION_FAILED`，冻结保持，可重试原申请或转人工。
- 渠道超时/未知：原申请保持未完成并保留冻结；恢复任务必须先按同一幂等键查询渠道，只有明确 `NOT_FOUND` 才能再次调用原申请。
- 重复执行、未审批执行、拒绝申请执行和已执行再次执行均返回稳定冲突/幂等结果，不创建新退款单。

## 人工审批结果

批准/拒绝只能由受授权的人工审批入口触发，不是 Agent Tool。结果必须保留 `operator`、`decidedAtMs`、`approvalId`、`refundNo`、`policyVersion` 和 `ruleIds` 等审计证据。

- 批准返回 `APPROVED` 和 `applicationStatus=APPROVED_PENDING_EXECUTION`，不执行资金动作。
- 拒绝返回 `REJECTED` 和 `applicationStatus=REJECTED`，同一事务释放履约冻结并恢复拒绝前订单状态。
- 重复或并发决策返回 `CONFLICT`，不能覆盖已有审批结果；事务失败返回 `FAILED`，申请、审批、冻结和订单状态保持原状。

## 其他规则

- `queryStatus=UNAVAILABLE` 或 `REFUND_POLICY_UNAVAILABLE` 时，只读调用最多重试一次；仍不可用就转人工。结果查询同样只允许一次瞬时失败重试。
- 写入结果未知时必须保留 trace/session 关联；查询和后续人工转接不得把未知解释成成功。
- 不把异常、未知结果或 `ELIGIBLE` 解释成“退款申请已受理”。
- 首期不支持部分退款；不得接收或计算用户指定的部分金额。
