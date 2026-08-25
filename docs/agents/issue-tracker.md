# Issue tracker: Local Markdown

本仓库是从上游拉取的项目。PRD 与实施任务只写入当前工作区的 `.scratch/`，不向上游 GitHub 创建 Issue 或 PR。

## Conventions

- 每个需求使用独立目录：`.scratch/<feature-slug>/`
- PRD 保存为：`.scratch/<feature-slug>/PRD.md`
- 实施任务保存为：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 开始编号
- 任务状态在文件顶部附近使用 `Status:` 记录；分诊取值及领取后的 `claimed`、完成后的 `resolved`
  生命周期取值见 `triage-labels.md`
- 补充讨论追加在任务文件底部的 `## Comments` 下

## Skill operations

- 当 Skill 要求“发布到议题跟踪器”时，在 `.scratch/<feature-slug>/` 下创建对应 Markdown 文件。
- 当 Skill 要求“读取相关任务”时，读取用户提供的文件路径或编号对应的本地 Markdown。
- 本地跟踪器没有 PR 请求入口，不执行外部 PR triage。

## Wayfinding operations

- Map：`.scratch/<effort>/map.md`
- Child ticket：`.scratch/<effort>/issues/NN-<slug>.md`
- 阻塞关系：在任务顶部使用 `Blocked by: NN, NN`
- Claim：开始处理前将 `Status:` 改为 `claimed`
- Resolve：在 `## Answer` 下记录结果，将 `Status:` 改为 `resolved`，并把上下文指针追加到 `map.md`
