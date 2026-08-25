# 订单物流 API 契约

## `queryOrder(orderId)`

只读查询。只有 `queryStatus=FOUND` 才进入物流查询；`NOT_FOUND` 和 `UNAVAILABLE` 不得伪造订单事实。

## `queryLogistics(orderId)`

只读查询，返回结构化结果：

```text
queryStatus: FOUND | NOT_FOUND | UNAVAILABLE
orderId: string
logisticsStatus: PENDING_SHIPMENT | IN_TRANSIT | OUT_FOR_DELIVERY | DELIVERED | UNKNOWN
freshnessStatus: FRESH | STALE | UNKNOWN
events: [{ occurredAt: string|null, description: string }]
lastEventAt: string|null
promisedDeliveryAt: string|null
reasonCodes: string[]
```

约束：

- `promisedDeliveryAt` 没有 Backend 依据时必须为 `null`；
- `freshnessStatus=UNKNOWN` 只能说明信息新鲜度未知，不能说明没有延误；
- `reasonCodes` 是 Backend/适配层事实，不是 Agent 自行编造的理由；
- `LOGISTICS_SIGNATURE_DISPUTE`、`LOGISTICS_LOST`、`LOGISTICS_DAMAGED` 表示签收/丢失/破损争议，必须转人工；
- 历史自由文本后端适配为 `LOGISTICS_LEGACY_TEXT` 时，按保守规则处理，不能当作完整时效证据。

## `transferToHuman(reason)`

仅用于异常、时效争议、签收争议或用户明确要求人工。`reason` 只传最小化、脱敏后的事实摘要；不传地址、支付信息和完整原始对话。
