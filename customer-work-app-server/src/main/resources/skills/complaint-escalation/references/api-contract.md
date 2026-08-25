# 投诉 API 契约

## `fileComplaint(orderId, summary, keyFacts, customerRequest, escalationReason, priority)`

写入前由工具做字段白名单、长度限制和脱敏。输入不应包含完整对话、身份证、完整地址、支付信息或未经核验的责任结论。

返回结构：

```text
submissionStatus: CREATED | NOT_FOUND | UNAVAILABLE | UNKNOWN
ticketId: string|null
priority: LOW | MEDIUM | HIGH | URGENT
status: PROCESSING | RESOLVED | UNKNOWN
reasonCodes: string[]
```

只有 `submissionStatus=CREATED` 且 `ticketId` 非空，才表示工单创建结果已确认。

## `queryComplaint(ticketId)`

只读查询，返回同一结构。`NOT_FOUND`、`UNAVAILABLE` 和 `UNKNOWN` 都不能被解释成“处理中”或“已解决”。

## `transferToHuman(reason)`

用于高风险、强烈情绪、明确人工、时效临界、重复未解决或工单结果未确认的场景。`reason` 只传脱敏事实摘要和升级原因。
