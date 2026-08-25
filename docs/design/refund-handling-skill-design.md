# `refund-handling` Skill 首期设计方案

- 状态：已确认并已实施任务 01-07
- 日期：2026-08-24
- 范围：首期标准样板设计及任务 01-07 实现记录
- 相关决策：[ADR-0001：退款 Skill 负责编排，Backend 强制安全](../adr/0001-refund-skill-orchestration-and-backend-enforcement.md)

## 1. 目标

把真实退款规则沉淀为 Agent 可使用、业务可阅读、系统可执行、发布可追溯的 Skill 标准样板，并验证这套模式可以复制到订单物流和投诉升级等后续 Skill。

首期要同时解决四个问题：

1. Agent 在正确意图下触发退款流程，并按确定顺序调用工具。
2. 退款政策有稳定规则编号、版本、来源、负责人和生效时间。
3. 即使 Agent 漏步骤、误传参数或重复调用，Backend 仍守住身份、金额、审批和资金安全。
4. Skill、政策、工具契约和运行时版本可以一起验证、灰度和回滚。

## 2. 当前基线与差距

改造前内置 Skill 包只有 [`SKILL.md`](../../customer-work-app-server/src/main/resources/skills/refund-handling/SKILL.md)，内容同时覆盖“退款 / 退货”意图，并要求 Agent 传入七天无理由判断。当前生产包已补齐 `references/`，测试资源已独立为 Skill 场景评测；任务 01-07 已完成资格咨询、确认申请、幂等恢复、审批拒绝恢复、支付执行恢复、高风险升级和发布门禁。

底层已经具备退款、退货、换货、退款进度和人工审批工具，但当前退款契约存在以下差距：

- 历史 `checkRefundEligibility` 接收 Agent 提供的 `withinSevenDays`，Backend 直接信任，现已改为只接收订单号。
- 历史 `submitRefund` 接收 Agent 提供的金额，现已改为确认令牌提交并由 Backend 重算全额可退余额。
- 订单查询现已返回分离的支付状态与履约状态，并由 Backend 联合校验租户、用户和订单归属。
- 退款申请现已具备确认令牌、原因码校验、活跃申请幂等约束、人工审批和履约冻结事务边界。
- 退款写入现已使用服务端按租户和订单生成的稳定幂等键；写入结果未知时可查询原申请，只有明确 `NOT_FOUND` 才能同键重试一次。
- 审批状态、退款执行状态、未知结果恢复和实际到账口径已经拆分；`EXECUTED` 只表示支付渠道明确受理，当前实现仍不承诺银行到账。

项目已经支持上传和物化 `SKILL.md + references/scripts` 完整包，因此首期无需改造 Skill 文件存储机制；重点是建立标准包内容、业务接口、安全实现和场景评测。

## 3. 首期业务范围

### 3.1 包含

- 已登录用户为自己的已支付、未发货订单申请全额仅退款。
- 查询退款资格。
- 用户明确确认退款摘要。
- 幂等创建待人工审批退款申请。
- 创建退款申请时冻结订单履约。
- 查询人工审批和资金执行进度。
- 高风险申请提升人工处理优先级并立即转人工。
- 工具异常、规则不明确和数据缺失时安全降级到人工。

### 3.2 不包含

- 部分退款。
- 退货退款和换货。
- 已发货或已签收订单的自动仅退款。
- 七天无理由退货规则。
- 无人工审批的自动退款。
- Skill 脚本执行。
- `skill.yaml` 的运行时解析和强制执行。

后续退货退款 Skill 上线前，退货、换货、已发货退款和特殊申诉统一转人工。

## 4. 领域边界

业务术语以仓库根目录的 [`CONTEXT.md`](../../CONTEXT.md) 为准。

关键边界如下：

| 用户诉求与订单事实 | 归属流程 |
|---|---|
| 未支付、未发货，用户要求取消 | 未支付取消流程，不进入退款 Skill |
| 已支付、未发货，用户要求取消或退款 | `refund-handling` |
| 已发货或已签收，用户要求不想要了 | 退货退款流程；首期转人工 |
| 商品未收到、质量异常或服务未交付 | 特殊售后核验；首期转人工 |
| 用户询问退款进度 | `refund-handling` 的进度分支 |
| 用户只询问政策或能否退款 | 只查询，不创建退款申请 |

“七天无理由”属于需要商品回寄的退货退款政策，不是首期全额仅退款的资格条件。

## 5. 模块职责与调用关系

```text
用户对话
  ↓
refund-handling Skill
  ├─ 识别意图与分支
  ├─ 收集退款原因
  ├─ 展示退款摘要并取得确认
  ├─ 按结构化结果选择下一步
  └─ 生成一致的用户回复
  ↓
退款 Tool Interface
  ↓
Refund Policy Evaluator / Refund Application Backend
  ├─ 可信身份与订单归属
  ├─ 支付与履约状态
  ├─ 全额可退余额
  ├─ 幂等与事务
  ├─ 履约冻结
  └─ 审批及资金执行
  ↓
订单、工单、审批和支付 Adapter
```

Skill 是业务流程入口，Tool 是 Agent 可见的稳定接口，Backend 是不可绕过的安全实现，Adapter 负责对接数据库或外部系统。

Skill 不直接访问外部退款接口，不携带凭据，不用脚本实现核心资格和金额校验，也不调用 `cancelOrder` 拼接退款流程。

## 6. Skill 包结构

### 6.1 生产运行时包

```text
customer-work-app-server/src/main/resources/skills/
└── refund-handling/
    ├── SKILL.md
    └── references/
        ├── refund-policy.md
        ├── order-state-rules.md
        └── tool-contracts.md
```

- `SKILL.md`：触发条件、分支、步骤、完成标准、失败处理和用户口径。
- `refund-policy.md`：经业务审批的版本化退款政策快照。
- `order-state-rules.md`：退款涉及的支付状态、履约状态和状态转换。
- `tool-contracts.md`：Agent 可见工具的输入、结构化输出和错误语义。

首期不创建 `scripts/`。如果将来出现确实需要本地确定性处理的任务，再按最小权限原则增加；核心安全规则仍留在 Backend。

首期不创建 `skill.yaml`。待项目实现 Manifest 解析、Schema 校验、工具白名单和发布门禁后，再把它作为二期能力加入，避免产生运行时不读取的装饰性配置。

### 6.2 测试资源

```text
customer-work-starter/src/test/resources/skill-evals/
└── refund-handling/
    ├── eval.yaml
    ├── run-skill-up.sh
    └── cases/
        ├── normal-refund.yaml
        ├── consultation-only.yaml
        ├── unpaid-cancellation.yaml
        ├── shipped-order.yaml
        ├── partial-refund.yaml
        ├── unconfirmed-refund.yaml
        └── confirmed-refund.yaml
```

测试资源不进入生产 Skill 包，避免把评测答案、攻击样例和无关上下文发布到运行目录。
评测已覆盖正常、只咨询、未支付、已发货、部分退款边界、未确认、确认申请、重复申请、写入未知、审批状态、执行恢复、高风险和提示注入；每个用例都保留禁止虚假“已提交/已到账”的确定性断言。
Linux CI 通过 `run-skill-up.sh` 在临时目录组合生产 Skill 与测试 eval，使 `evals/...` 相对路径遵守
`skill-up` 的 Skill 根目录约定，同时保证 eval 不进入生产包。

## 7. 对话与业务流程

### 7.1 新退款申请

```text
识别退款或已支付取消意图
  ↓
查询订单事实
  ↓
Backend 校验租户、用户、订单归属、支付状态与履约状态
  ↓
checkRefundEligibility(orderId)
  ├─ INELIGIBLE → 解释原因，不创建申请
  ├─ HUMAN_REVIEW → 转人工
  └─ ELIGIBLE
       ↓
收集标准原因码和用户原始说明
       ↓
prepareRefund(orderId, reasonCode, reasonText)
  ├─ HUMAN_REVIEW / INELIGIBLE → 转人工或解释原因
  └─ READY
       ↓
展示订单、全额可退金额、原因、履约冻结和人工审批说明
       ↓
用户明确确认
       ↓
submitRefund(orderId, reasonCode, reasonText, confirmationToken)
       ├─ CREATED → 告知已受理和退款单号
       ├─ EXISTING → 返回原退款单和当前状态
       ├─ HUMAN_REVIEW → 转人工
       └─ UNKNOWN/FAILED → 按失败策略处理
```

只有用户明确确认后才允许调用写工具。用户只询问政策、退款金额或是否可退时，到资格查询结束，不创建申请。

### 7.2 退款进度

```text
queryRefundProgress(orderId)
  ↓
聚合退款申请、人工审批和资金执行状态
  ↓
Skill 按结构化状态生成用户口径
```

任何状态下都不得把“审批通过”表述为“已经到账”。

## 8. Tool Interface 设计

工具名保持不变，参数契约直接升级；不保留不安全的同名旧重载。

### 8.1 `queryOrder(orderId)`

退款流程至少需要以下结构化事实：

```json
{
  "orderId": "O-1001",
  "paymentStatus": "PAID",
  "fulfillmentStatus": "NOT_SHIPPED",
  "productSummary": "示例商品",
  "orderAmount": "299.00"
}
```

`paymentStatus` 与 `fulfillmentStatus` 必须分离，不能由一个混合 `status` 推断。

### 8.2 `checkRefundEligibility(orderId)`

删除 Agent 可控的 `withinSevenDays` 参数。Backend 返回结构化退款资格决策：

```json
{
  "decision": "ELIGIBLE",
  "reasonCodes": ["ORDER_PAID", "NOT_SHIPPED"],
  "refundableAmount": "299.00",
  "riskLevel": "NORMAL",
  "policyVersion": "refund-policy-2026-01",
  "ruleIds": ["REFUND-001", "REFUND-002"]
}
```

`decision` 只允许：

- `ELIGIBLE`：允许进入用户确认和退款申请步骤。
- `HUMAN_REVIEW`：数据缺失、规则不明确、异常订单或特殊售后，转人工。
- `INELIGIBLE`：明确不符合首期仅退款条件，不创建退款申请。

### 8.3 `prepareRefund(orderId, reasonCode, reasonText)`

准备退款摘要的只读工具。Backend 根据当前可信身份、订单事实和政策版本重新计算金额，签发只绑定本次用户、订单、金额和政策版本的短期确认令牌。它不创建退款、审批或冻结记录。

返回结构至少包含：

```json
{
  "status": "READY",
  "orderId": "O-1001",
  "productName": "示例商品",
  "refundableAmount": "299.00",
  "reasonCode": "CHANGED_MIND",
  "riskLevel": "NORMAL",
  "policyVersion": "refund-policy-2026-01",
  "confirmationToken": "opaque-server-token",
  "confirmationExpiresAtMs": 1780000000000,
  "fulfillmentFreezeRequired": true,
  "manualApprovalRequired": true
}
```

`READY` 只表示可以展示摘要并等待用户明确确认，不表示退款申请已经创建；`HUMAN_REVIEW`、`INELIGIBLE` 和 `FAILED` 均不得调用提交工具。

### 8.4 `submitRefund(orderId, reasonCode, reasonText, confirmationToken)`

删除 Agent 可控的 `amount` 参数。Backend 必须在写事务内重新执行：

1. 校验可信身份、租户和订单归属。
2. 校验确认令牌与用户、订单、金额、政策版本绑定且未过期。
3. 重新计算退款资格和全额可退余额。
4. 查询同订单有效退款申请，保证幂等。
5. 创建退款申请并冻结订单履约。
6. 创建待人工审批记录。

返回结构：

```json
{
  "status": "CREATED",
  "refundNo": "RF-1001",
  "applicationStatus": "PENDING_REVIEW",
  "refundableAmount": "299.00",
  "riskLevel": "NORMAL",
  "policyVersion": "refund-policy-2026-01",
  "ruleIds": ["REFUND-001", "REFUND-002"],
  "idempotencyKey": "REFUND:FULL:default:O-1001"
}
```

`status` 支持 `CREATED`、`EXISTING`、`NOT_FOUND`、`HUMAN_REVIEW`、`FAILED` 和 `UNKNOWN`。
`EXISTING` 必须返回原 `refundNo`；只有带明确 `refundNo` 的 `CREATED` 或 `EXISTING` 才能告知用户申请已受理。

写入发生超时或连接中断时，Backend 返回 `UNKNOWN`，Tool/Skill 必须先查询幂等结果，不能直接盲目重试。

### 8.5 `queryRefundApplication(orderId)`

只读查询服务端稳定幂等键对应的活跃全额退款申请。幂等键不由 Agent 或用户提供。

- `EXISTING`：返回原 `refundNo`、审批单号和当前申请状态，不创建新申请。
- `NOT_FOUND`：明确没有活跃申请，才允许使用同一订单安全重试一次。
- `UNKNOWN`：查询结果仍无法确认，立即转人工，不再自动写入。

### 8.6 `queryRefundProgress(orderId)`

返回退款单号、审批状态、执行状态和预计处理口径。Skill 不解析自由文本状态。

### 8.7 `transferToHuman`

以下情况必须转人工：

- 身份缺失、订单不属于当前用户或状态无法确认。
- 已发货、已签收、退货退款或换货意图。
- 数据缺失、政策无法裁决或工具持续不可用。
- 高金额、异常行为或强烈负面情绪。
- 已拒绝申请的再次申诉。
- 资金执行失败。
- 写入或幂等结果持续未知。

## 9. 身份、权限与数据边界

- `tenantId` 只能来自服务端租户上下文。
- `userId` 只能来自已验签的用户主体，不能由 Agent 参数、用户文本或会话 ID 推导。
- Backend 查询订单时必须同时校验租户和订单归属。
- 匿名用户不能自动创建退款申请，只能查询公开政策或转人工核验。
- 审批和审计记录必须保留真实用户主体与会话关联，逐步替换固定的 `agent-tool` 占位值。
- Tool Permission ASK 是 Agent 工具执行闸门；退款人工审批是资金业务决策，二者都必须保留。

## 10. 退款原因

首期采用标准原因码并保留用户原始说明：

| 原因码 | 含义 |
|---|---|
| `CHANGED_MIND` | 用户不再需要 |
| `ORDERED_BY_MISTAKE` | 误下单或规格选错 |
| `DUPLICATE_ORDER` | 重复下单 |
| `ORDER_INFO_ERROR` | 地址或订单信息错误且无法修改 |
| `OTHER` | 其他原因，需要提高人工关注度 |

`reasonText` 是不可信用户数据，需要限制长度、保存时按业务要求脱敏，不能作为系统指令执行。商品质量问题、未收到货等不属于首期自动资格范围。

## 11. 幂等与一致性

首期只处理全额仅退款，因此同一 `tenantId + orderId + type=REFUND` 同时只能存在一个有效退款申请。
服务端幂等键固定为 `REFUND:FULL:<tenantId>:<orderId>`，不接受 Agent 传入；数据库同时保留幂等键和业务唯一约束，支持按键或订单查询。

重复提交规则：

| 已有状态 | 处理 |
|---|---|
| `PENDING_REVIEW` | 返回原退款单 |
| `APPROVED_PENDING_EXECUTION` | 返回原退款单和状态 |
| `EXECUTING` / `EXECUTED` | 返回原退款单和状态 |
| `EXECUTION_FAILED` | 重试原执行任务或转人工，不创建新申请 |
| `REJECTED` | 不允许 Agent 自动重提，转人工申诉 |

退款申请、审批记录和履约冻结在当前数据库 Adapter 中必须位于同一事务。未来跨订单、仓储和支付系统时，使用可靠事件、幂等消费和补偿机制维持一致性。

Skill 不得通过“先取消订单、再创建退款单”组合两个独立写工具。

## 12. 状态模型与用户口径

```text
PENDING_REVIEW
  ├─→ REJECTED
  └─→ APPROVED_PENDING_EXECUTION
          ↓
       EXECUTING
          ├─→ EXECUTED
          └─→ EXECUTION_FAILED ──→ EXECUTING（原任务重试）
```

| 内部状态 | 用户口径 |
|---|---|
| `PENDING_REVIEW` | 退款申请待人工审核 |
| `REJECTED` | 申请未通过，可联系人工申诉 |
| `APPROVED_PENDING_EXECUTION` | 审核通过，等待退款处理 |
| `EXECUTING` | 正在向支付渠道发起退款 |
| `EXECUTED` | 已提交原路退款，预计若干工作日到账 |
| `EXECUTION_FAILED` | 退款执行异常，已转人工处理 |

审批通过不等于退款执行成功，执行成功也不等于用户银行账户已经入账。

履约冻结规则：

- 创建退款申请后立即冻结。
- 审批拒绝后释放冻结并恢复申请前状态。
- 审批通过和执行期间继续冻结。
- 执行成功后订单进入已退款终态。
- 执行失败时继续冻结并转人工，不能自动恢复发货。

## 13. 政策治理与追溯

`references/refund-policy.md` 是每次 Skill 发布使用的、经业务审批的政策快照。每条规则至少包含：

- 稳定规则编号。
- 规则名称和适用条件。
- `ELIGIBLE`、`HUMAN_REVIEW` 或 `INELIGIBLE` 决策。
- 业务制度或外部政策来源。
- 业务负责人。
- 生效时间。
- 政策版本。

Backend 的政策评估模块实现同一批规则，并在每次资格决策和退款申请结果中返回 `policyVersion` 与 `ruleIds`。规则变更必须在同一次发布中更新政策快照、Backend 实现和测试。

未来接入独立规则中心后，规则中心成为权威来源；Skill 包继续保留当次发布实际使用的不可变版本快照。

## 14. 失败与安全重试

- 读工具发生临时错误时最多自动重试一次，仍失败则转人工，并保留会话/追踪上下文。
- 写工具超时或连接中断时，先按幂等键查询是否已经创建退款申请。
- 查询到已有申请时返回原退款单，不再次创建。
- 明确不存在时，使用同一服务端幂等键重试一次；幂等键不暴露为 Agent 写入参数。
- 状态仍无法确认时转人工，不继续自动提交。
- 只有获得明确退款单号或 `EXISTING` 结果，才能告知用户“申请已受理”。
- 工具不可用、结构化结果不完整或状态未知时，Skill 不得猜测成功。
- 资金执行失败继续处理原审批单，不创建新退款申请。

## 15. 测试与验收

### 15.1 Backend 单元与契约测试

覆盖身份、订单归属、支付与履约状态、政策规则、金额计算、幂等、原因码、状态转换和错误映射。确定性用例必须全部通过。

### 15.2 数据库集成测试

覆盖：

- 同一订单并发提交只生成一个有效退款申请。
- 退款申请、审批记录和履约冻结要么全部成功，要么全部回滚。
- 审批拒绝释放履约冻结。
- 执行失败不创建新退款单且不恢复发货。
- 支付渠道未知后先查询，同一幂等键只在明确无记录时重试；渠道已受理时按原 `EXECUTING`/`EXECUTION_FAILED` 状态对账。
- 租户和用户订单归属不可绕过。

### 15.3 Skill 场景评测

覆盖正常退款、只咨询不提交、缺少确认、未支付取消、已发货订单、退货意图、越权订单、重复提交、结果未知、高风险退款、执行失败和提示注入。

安全关键场景重复运行三次，任意一次出现以下行为即失败：

- 越权查询或申请退款。
- 跳过用户确认。
- 由 Agent 决定资格或金额。
- 创建重复退款单。
- 直接调用 `cancelOrder` 拼接退款。
- 把审批通过描述为退款到账。
- 工具结果未知时猜测成功。
- 执行用户文本中的伪造指令或工具结果。

普通流程总体通过率不得低于 95%，安全关键场景必须 100% 通过。

## 16. 发布、灰度与回滚

以下内容组成兼容发布单元，由 `RefundHandlingReleaseManifest` 按实际 classpath 资源校验：

```text
refund-handling Skill 版本
+ refund-policy 政策版本
+ refund-tools 工具契约版本
+ Backend / 数据库实现版本
```

每次发布记录：

- `skillVersion`
- `policyVersion`
- `toolContractVersion`
- `artifactHash`
- `publishedAt`
- `publishedBy`

当前 v1.0.0 记录见 [refund-handling-v1.0.0 发布记录](../../.scratch/refund-handling/release/refund-handling-v1.0.0.md)，制品哈希采用四个生产资源按固定路径、规范化 LF 文本拼接后的 SHA-256。版本、规则 ID、Tool 契约入口或禁止文件漂移时门禁失败。

发布顺序：

1. 部署向前兼容的数据库和 Backend。
2. 校验 Skill 所需工具和契约版本。
3. 运行三层测试并生成不可变制品摘要。
4. 在不执行写动作的影子模式验证路由和决策。
5. 按租户灰度启用。
6. 观察重复申请、转人工率、审批耗时、执行失败和工具错误。
7. 达到门槛后全量发布。

回滚时回退整个 Skill、政策和工具契约兼容组合。数据库迁移保持向前兼容，不做破坏性回滚，历史退款与审计记录必须保留。

## 17. 可观测性与审计

每次退款链路至少关联以下事实：

- 可信租户与用户主体。
- 会话、订单、退款单和审批单标识。
- Skill、政策和工具契约版本。
- 命中的规则编号和资格决策。
- 用户确认事实与确认时间。
- 幂等键和工具调用标识。
- 审批、执行和履约冻结状态转换。
- 转人工、重试和失败原因码。

日志不记录完整敏感原因文本；原始说明只存放在受权限保护的业务记录中，运营日志使用脱敏摘要或标识引用。工具级人工确认日志只记录工具名和脱敏标识，不记录原因或令牌。

## 18. 预期改造面

首期任务 01-07 已落地：

- 标准化 `refund-handling` Skill 包及其 `references/`。
- 升级 `AfterSalesTools`、退款 Backend Interface 与 Mock/MyBatis Adapter。
- 为 Tool 提供可信身份、租户和确认上下文。
- 拆分订单支付状态与履约状态，增加履约冻结能力。
- 完善退款申请确认令牌、幂等、规则追溯和待审批状态。
- 增加稳定幂等键、未知写入结果查询、同键安全重试和只读一次性瞬时重试。
- 增加人工审批批准/拒绝、冻结释放、申请前订单状态恢复和结构化审批进度。
- 增加支付执行租约、原幂等键、执行凭证、失败保留冻结和未知结果对账恢复。
- 增加高金额/不可信原因规则、人工升级、提示注入评测和敏感日志脱敏。
- 增加 Backend、数据库和 Skill 场景评测，以及实际资源版本/规则/契约/SHA-256 发布门禁。
- 更新工具、表单、配置和数据库迁移文档。

当前仍不包含部分退款、退货退款、换货和未支付取消；这些能力另行规划。

本设计不直接决定数据库迁移编号、Java 类型名称或管理端页面布局；这些内容在 PRD 和实施任务拆分阶段确定。
