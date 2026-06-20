<!-- gitnexus:start -->
# GitNexus - 代码智能

本项目已由 GitNexus 索引为 **ThinkingCoding**（3265 个符号、7752 条关系、283 条执行流）。请使用 GitNexus MCP 工具来理解代码、评估影响并安全导航。

> 索引过期？在项目根目录运行 `node .gitnexus/run.cjs analyze` - 它会自动选择可用的运行器。还没有 `.gitnexus/run.cjs`？运行 `npx gitnexus analyze`（npm 11 崩溃时改用 `npm i -g gitnexus`；#1939）。


## 严禁执行

- 未先对函数、类或方法做 `impact`，不要编辑它。
- 不要忽略影响分析返回的 HIGH 或 CRITICAL 风险。
- 不要用查找替换来重命名符号 - 请使用理解调用图的 `rename`。
- 提交前不要忘记运行 `detect_changes()` 检查受影响范围。

## 资源

| 资源 | 用途 |
|------|------|
| `gitnexus://repo/ThinkingCoding/context` | 代码库概览，检查索引新鲜度 |
| `gitnexus://repo/ThinkingCoding/clusters` | 所有功能区域 |
| `gitnexus://repo/ThinkingCoding/processes` | 所有执行流 |
| `gitnexus://repo/ThinkingCoding/process/{name}` | 逐步执行轨迹 |

## CLI

| 任务 | 阅读此技能文件 |
|------|---------------------|
| 理解架构 / “X 是怎么工作的？” | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| 爆炸半径 / “修改 X 会影响什么？” | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| 追踪 bug / “X 为什么失败？” | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| 重命名 / 提取 / 拆分 / 重构 | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| 工具、资源、schema 参考 | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| 索引、状态、清理、wiki CLI 命令 | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
