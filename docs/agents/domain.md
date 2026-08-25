# Domain Docs

本仓库采用单一领域上下文。工程 Skills 在探索或修改代码前，应读取与当前工作相关的领域词汇和架构决策。

## Required reading

- 根目录 `CONTEXT.md`
- `docs/adr/` 中与当前工作相关的 ADR

文件不存在时静默继续；只有在领域术语或重要决策真正确定后，才通过领域建模流程按需创建。

## Layout

```text
/
├── CONTEXT.md
└── docs/
    └── adr/
```

## Vocabulary

任务标题、设计、测试名称和实现说明应使用 `CONTEXT.md` 定义的术语，不使用其中 `_Avoid_` 列出的同义词。

发现现有设计与 ADR 冲突时，必须明确指出冲突及重新评估理由，不能静默覆盖既有决策。
