# Triage Labels

工程 Skills 使用五个标准角色。本地任务文件通过顶部的 `Status:` 使用下表中的标签。

| Canonical role | Local label | Meaning |
|---|---|---|
| `needs-triage` | `needs-triage` | 等待维护者评估 |
| `needs-info` | `needs-info` | 等待补充信息 |
| `ready-for-agent` | `ready-for-agent` | 规格完整，可由 Agent 独立领取 |
| `ready-for-human` | `ready-for-human` | 需要人工实施或决策 |
| `wontfix` | `wontfix` | 不再处理 |

当 Skill 使用“AFK-ready”等角色描述时，使用对应的本地标签。需要调整词汇时，只修改本表的 `Local label` 列。

任务被领取后可进入 `claimed`，完成并记录 `## Answer` 后进入 `resolved`。这两个值是本地实施生命周期状态，
不是 triage 角色标签。
