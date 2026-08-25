---
name: order-logistics
description: 查询用户自己订单的结构化物流事实，解释物流阶段和轨迹；信息不足、状态异常、时效争议或签收争议时升级人工。不要执行改址、取消、催发货或任何赔偿动作。
---

<!-- version: 1.0.0 -->

# 订单物流查询

本 Skill 只编排“确认订单—查询物流事实—解释已知状态—异常升级”流程。物流状态、轨迹、最近事件时间、承诺时效依据和信息新鲜度必须来自 Backend；Agent 不估算到达时间，不把未知说成正常，也不调用订单写操作。

## 使用前读取

- 解释物流政策和可追溯规则时，读取 [物流政策快照](references/logistics-policy.md)。
- 调用工具或解释结构化结果时，读取 [API 契约](references/api-contract.md)。
- 判断是否需要人工时，读取 [异常与升级规则](references/escalation-rules.md)。

## 标准流程

1. 从已验证用户上下文取得订单号；没有订单号时请用户提供，不猜订单号，也不要把 sessionId、用户文本或模型推断当作订单号。
2. 调用只读 `queryOrder(orderId)`，确认订单事实和订单号有效。
3. 订单查询成功后调用只读 `queryLogistics(orderId)`。只使用返回的 `queryStatus`、`logisticsStatus`、`events`、`lastEventAt`、`promisedDeliveryAt`、`freshnessStatus` 和 `reasonCodes`。
4. 按结果处理：
   - `queryStatus=NOT_FOUND`：说明没有查到该订单，不猜测物流；请用户核对订单号。
   - `queryStatus=UNAVAILABLE`：说明物流系统暂不可用；不要把失败说成无轨迹，按升级规则转人工。
   - `logisticsStatus=PENDING_SHIPMENT`、`IN_TRANSIT`、`OUT_FOR_DELIVERY`、`DELIVERED`：只说明当前阶段和最近轨迹。
   - `logisticsStatus=UNKNOWN`、轨迹为空或 `freshnessStatus=STALE`：视为物流异常，转人工。
   - `reasonCodes` 包含 `LOGISTICS_SIGNATURE_DISPUTE`、`LOGISTICS_LOST` 或 `LOGISTICS_DAMAGED`：即使状态显示为已签收，也必须转人工，不得只复述状态。
   - `freshnessStatus=UNKNOWN`：可以在用户只询问“最近一条轨迹”时展示已返回事实，但不能判断是否按时、不能回答确定 ETA；用户询问延误、何时到或承诺时效时转人工。
5. 回复时明确事实来源和未知项。没有 `promisedDeliveryAt` 就不要说“预计 X 天/今天到”；没有明确新鲜度就不要说“运输正常”或“没有延误”。
6. 只有触发升级规则时调用 `transferToHuman(reason)`。升级原因使用脱敏、事实化摘要，不写入完整对话、地址、支付信息或用户自行声称的责任结论。

## 分流边界

- `modifyAddress`、`cancelOrder`、`urgeShipment` 不属于本 Skill 主流程；用户提出这些诉求时转对应业务流程或人工，不得与物流查询拼接成自动写操作。
- 物流异常不等同于退款资格，不得直接调用退款、赔偿或支付工具。
- 用户要求投诉时进入 `complaint-escalation`，不要用物流 Skill 代替投诉建单。

## 安全边界

- `queryOrder` 和 `queryLogistics` 是只读事实查询；工具返回空值、未知、错误或自由文本适配标记时，按未知处理。
- Backend 没有提供承诺时效时，`promisedDeliveryAt` 必须为空；Agent 不得从商品、地区或历史经验推算。
- Backend 没有提供新鲜度时，`freshnessStatus` 必须为 `UNKNOWN`；脚本校验只能阻止不完整结果，不能制造新鲜度。
- 工具调用失败或结果不确定时，不通过重复写操作“补救”；最多使用只读工具的既有安全重试，仍不确定就人工升级。
- 生产 Skill 脚本是校验/测试防线，不是 Agent 可直接调用的工具；权限、订单归属和写操作安全由 Backend 强制执行。
