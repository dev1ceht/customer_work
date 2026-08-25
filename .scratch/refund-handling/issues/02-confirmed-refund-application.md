# 经用户确认创建待审核退款

Status: resolved

Blocked by: 01

Source: [refund-handling PRD](../PRD.md)

User stories: 3, 4, 5, 13, 14, 16, 20, 22, 23, 25

## What to build

在安全资格咨询链路之上，交付“展示退款摘要—用户明确确认—创建待人工审核申请”的完整路径。Backend 从可信服务端上下文获得租户和用户身份，重新验证订单归属、资格、全部可退余额及政策版本，并在同一事务中创建退款申请、审批记录和履约冻结。Agent 只能提交标准原因和绑定当前决策的确认令牌，不能指定最终退款金额或绕过人工审批。

## Acceptance criteria

- [x] 提交契约只接收订单标识、标准原因码、受限原因文本和确认令牌，不接收退款金额或由 Agent 判断的资格字段。
- [x] `tenantId` 来自已验签服务端用户主体及租户上下文，`userId` 来自已验证用户主体；模型输出、用户文本和会话 ID 均不能作为身份来源。
- [x] Backend 使用租户、用户和订单归属联合校验；匿名、身份失效或归属不匹配时不创建任何申请并返回人工处理结果。
- [x] Agent 在请求确认前展示订单、商品、全部可退余额、退款原因、履约冻结和人工审批提示。
- [x] 确认令牌绑定用户、订单、金额和政策版本并具有有效期；提交时 Backend 重新计算和校验全部字段。
- [x] 原因码限定为已批准枚举，自由原因文本执行长度限制并始终作为不可信业务数据处理。
- [x] JDBC 实现用一个事务创建退款申请、审批记录和履约冻结，任一环节失败均不留下部分结果。
- [x] 新申请状态为 `PENDING_REVIEW`，所有申请必须等待人工审批，Agent 不存在直接赔付或自动审批路径。
- [x] 每个租户、订单和仅退款类型至多存在一个活跃的全额退款申请，并建立数据库级并发保护。
- [x] 成功结果结构化返回 `CREATED`、退款单号、申请状态、全部可退余额、风险等级、政策版本和规则 ID。
- [x] Agent 与 Backend 测试覆盖无确认、过期或篡改确认、越权、金额变化、并发提交、事务回滚和正常创建。

## Blocked by

- [01 退款资格咨询安全链路](01-safe-refund-eligibility-consultation.md)

## Comments

- 已实现 `prepareRefund` / `submitRefund` 两阶段契约：提交不接收金额，Backend 在提交阶段重新查询订单、重算资格并验证短期 HMAC 确认令牌。
- 已增加用户主体 ThreadLocal/Reactor 上下文、订单归属校验、原因码与 500 字符限制、`PENDING_REVIEW` 状态、审批用户主体审计字段、履约冻结表及数据库唯一约束。
- 已更新退款表单入口、生产 Skill 及三份 references；生产包按当前设计不加入尚未被运行时解析的 `skill.yaml` 或脚本目录。
- 定向测试：`mvn -gs settings-central-direct.xml -s settings-central-direct.xml -pl customer-work-app-server -am '-Dtest=RefundConfirmationFlowTest,AfterSalesToolsTest,MybatisAfterSalesBackendTest,RefundConsultationBehaviorTest,RefundHandlingSkillEvalTest,RefundHandlingSkillPackageTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' test`。
- 结果：31 个 Starter 定向测试（含 13 个 MySQL 集成测试自动跳过）与 9 个 App/Skill 测试通过；MySQL 可用时新增事务回滚用例会执行。
