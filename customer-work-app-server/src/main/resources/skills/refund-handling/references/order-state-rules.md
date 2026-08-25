# 订单状态规则

退款咨询只消费 `queryOrder` 返回的独立 `paymentStatus` 与 `fulfillmentStatus`，不要从自然语言或单一混合状态自行推断。

## 支付状态

| `paymentStatus` | 含义 | 退款分流 |
|---|---|---|
| `UNPAID` | 尚未发生支付 | 未支付取消流程 |
| `PAID` | 已支付且尚未完成退款 | 继续结合履约状态判断 |
| `REFUNDED` | 已完成退款终态 | 不重复退款 |
| `UNKNOWN` | 无法可靠确认支付事实 | 人工核验 |

## 履约状态

| `fulfillmentStatus` | 含义 | 退款分流 |
|---|---|---|
| `NOT_SHIPPED` | 尚未发货 | 已支付时可继续资格检查 |
| `SHIPPED` | 已发货 | 退货退款或人工 |
| `DELIVERED` | 已签收 | 退货退款或人工 |
| `CANCELLED` | 履约已取消 | 不创建退款申请 |
| `CLOSED` | 已退款等关闭终态 | 不重复退款 |
| `UNKNOWN` | 履约事实不完整 | 人工核验 |

## 当前存量状态的保守映射

| 存量订单状态 | `paymentStatus` | `fulfillmentStatus` |
|---|---|---|
| 待支付 | `UNPAID` | `NOT_SHIPPED` |
| 已支付 / 待发货 | `PAID` | `NOT_SHIPPED` |
| 已发货 | `PAID` | `SHIPPED` |
| 已签收 | `PAID` | `DELIVERED` |
| 已取消 | `UNKNOWN` | `CANCELLED` |
| 已退款 | `REFUNDED` | `CLOSED` |
| 其他值 | `UNKNOWN` | `UNKNOWN` |

“已取消”可能发生在支付前或支付后；当前存量单列状态无法证明支付事实，因此必须保守返回 `UNKNOWN`，不能猜测。
