# 物流异常与升级规则

## 立即升级

- `queryStatus=UNAVAILABLE` 且只读安全重试后仍不可用；
- `logisticsStatus=UNKNOWN`、轨迹为空或 `freshnessStatus=STALE`；
- `reasonCodes` 包含 `LOGISTICS_SIGNATURE_DISPUTE`、`LOGISTICS_LOST` 或 `LOGISTICS_DAMAGED`；
- 用户明确反馈延误、超出承诺时效、包裹丢失、破损或签收争议；
- 用户要求投诉、人工坐席或涉及金额/安全/法律风险。

## 可以直接说明

- 订单号有效、物流状态明确、至少有一条轨迹事件，且用户只询问“当前到哪里”；
- 即使 `freshnessStatus=UNKNOWN`，也只能展示最近返回的事件，并明确“当前没有可验证的新鲜度/承诺时效信息”。

## 禁止口径

- “肯定今天送达”“预计两小时到”“物流正常且没有延误”；
- “已为您催发货”“已修改地址”“已赔偿”；
- 未查询到订单时根据用户描述补写物流轨迹。
