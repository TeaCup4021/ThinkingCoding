# 上下文重构 & 相关问题修复记录

> 日期: 2026-05-08  
> 涉及文件: 6 个核心文件，+306 / -140 行

---

## 一、改动内容总览

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `ThinkingCodingUI.java` | 美化 | 替换启动 Banner ASCII 艺术字 |
| `ContextManager.java` | 重构 | 拆分 System Message，新增 Current Context 构建 |
| `AgentOrchestrator.java` | 重构 + BugFix | Plan/TODO 流程改造，上下文数据回传 |
| `LangChainService.java` | 重构 | `prepareMessages` 改为三段式组装 |
| `TodoTracker.java` | BugFix | `updateStatus` 内容匹配 fallback，ID 展示 |
| `MCPToolAdapter.java` | 次要 | 日志/导入调整 |

---

## 二、功能改动详情

### 2.1 Banner ASCII 艺术字替换

**文件**: `ThinkingCodingUI.java:71-120`

**改动**: 将原来 `░` 字符拼凑、难以辨认的 11 行 ASCII 图案，替换为 FIGlet **slant** 字体的 "ThinkingCoding"，6 行、72 字符宽。

**旧**:
```
░██████████░██  ░██  ░██  ░██████  ...  (11行, 122字符宽, 难以辨认)
```

**新**:
```
  ________    _       __   _             ______          ___
 /_  __/ /_  (_)___  / /__(_)___  ____ _/ ____/___  ____/ (_)___  ____ _
  / / / __ \/ / __ \/ //_/ / __ \/ __ `/ /   / __ \/ __  / / __ \/ __ `/
 / / / / / / / / / / ,< / / / / / /_/ / /___/ /_/ / /_/ / / / / / /_/ /
/_/ /_/ /_/_/_/ /_/_/|_/_/_/ /_/\__, /\____/\____/\__,_/_/_/ /_/\__, /
                               /____/                          /____/
           AI-Powered Interactive Code Assistant
                          v2.0.0
```

---

### 2.2 上下文三分法重构

**目标**: 将原来扁平的上下文组装改为混合上下文组装的策略：

```
┌──────────────────────────────┐
│ Part 1: System Message       │  ← 角色定义、行为规范、Skills
├──────────────────────────────┤
│ Part 2: History Messages     │  ← User/Assistant 原生角色，经 micro_compact + 滑动窗口/LLM摘要
├──────────────────────────────┤
│ Part 3: Current Context      │  ← 当前目标 + 可用工具 + 上一轮执行结果，拼接到最新 UserMessage
└──────────────────────────────┘
```

#### 2.2.1 `ContextManager.java`

**新增字段**:
```java
private volatile String lastPlanSummary;   // 上一轮 Plan 摘要
private volatile String lastToolResults;   // 上一轮工具执行结果
```

**新增方法**:
- `setLastPlanSummary(String)` / `setLastToolResults(String)` — AgentOrchestrator 在 turn 结束时写入
- `buildCurrentContext(Collection<BaseTool>)` — 拼接"当前目标 + 可用工具 + 上一轮执行结果"

**精简 System Message**:
- 移除了硬编码的工具列表（~35 行），改为 6 行行为规则
- 工具列表改为动态从 `ToolRegistry` 获取，放入 Current Context

#### 2.2.2 `AgentOrchestrator.java`

**新增方法**:
- `saveCurrentContext(PlanResult, ExecuteReactResult)` — turn 结束时将 Plan 摘要和工具结果写入 ContextManager
- `extractTaskSummary(PlanResult)` — 从 assistant text 中提取简洁摘要（第一句话，最多 100 字）
- `refreshTodoFromPlan` 重写 — 支持从普通工具调用自动生成 TODO

**流程调整**:
- `executeResult` 作用域提升为方法级变量
- `onUserInput()` 末尾调用 `saveCurrentContext()`（在 `sessionGateway.save()` 之前）
- assistant 回复以正确的 `assistant` role 写入 history

#### 2.2.3 `LangChainService.java`

**重写 `prepareMessages()`**:
```java
// Part 1: SystemMessage
messages.add(SystemMessage.from(buildProjectContextMessage()));

// Part 2: 压缩后的历史
messages.addAll(convertToLangChainHistory(getContextForAI(history)));

// Part 3: 最新 UserMessage + Current Context
String augmentedInput = input + buildCurrentContext(toolRegistry.getAllTools());
messages.add(UserMessage.from(augmentedInput));
```

---

## 三、问题 & 修复记录

### 问题 1: 上轮 assistant 回复泄漏到下一轮的 Plan 中

**现象**: 第一轮问"你是谁？"，AI 的自我介绍在第二轮"创建仓库"的 Plan 中重复出现。

**原因**:
1. `formatPlanSummary()` 包含 `planResult.getAssistantText()`（完整 AI 回复），被存入 history 的 `system` role 消息
2. `saveCurrentContext()` 用同一个 `formatPlanSummary()` 的结果作为 `lastPlanSummary`
3. 下一轮 `buildCurrentContext()` 把这段完整自我介绍作为"当前目标"拼入用户消息

**修复** (3 处):
1. assistant 回复改为以 `assistant` role 写入 history（而非包在 `<plan>` 里的 `system` role）
2. no-tool-call 分支不再把 plan summary 重复写入 history
3. `saveCurrentContext()` 使用 `extractTaskSummary()` 提取简洁摘要（第一句话 ≤100 字），而非完整 plan 文本

**涉及文件**: `AgentOrchestrator.java`

---

### 问题 2: Plan 展示缺少 AI 方案分析

**现象**: 修复问题 1 时，`formatPlanSummary()` 被改为只输出工具调用列表，导致 Plan 展示中看不到 AI 对需求的分析和方案计划。

**原因**: 矫枉过正——为了解决 Current Context 污染问题，错误地把 `assistantText` 从 Plan 展示中也移除了。

**修复**: 恢复 `formatPlanSummary()` 中的 `assistantText`，格式为：
```
<plan>
方案计划:
<AI 的分析和方案文本>

工具调用:
-1 write_file {path=..., content=...}
</plan>
```

同时 `saveCurrentContext()` 改用 `extractTaskSummary()` 获取简洁摘要，保证"当前目标"不受污染。

**涉及文件**: `AgentOrchestrator.java`

---

### 问题 3: TODO 列表为空

**现象**: Plan 确认后 TODO 列表为空，AI 无法跟踪任务进度。

**原因**: `refreshTodoFromPlan()` 只从 `manage_todo` 工具调用的 `action=add` 参数中提取 TODO，但 AI 很少主动调用 `manage_todo`，导致 TODO 列表始终为空。

**修复**: 重写 `refreshTodoFromPlan()`：
1. 先检查是否有 AI 显式声明的 `manage_todo(action="add", ...)` 条目
2. 如果没有，从普通工具调用中自动生成 TODO：
   - 提取 `path` / `name` / `target` / `command` 参数作为描述
   - 生成格式: `write_file D:\helloXUPT.java`

**涉及文件**: `AgentOrchestrator.java`

---

### 问题 4: TODO 标记完成时报 "未找到对应 TODO"

**现象**: 工具执行成功后，AI 调用 `manage_todo(action="complete", id="write_file")`，系统返回 `未找到对应 TODO: write_file`。

**原因**: 
1. `resetWithContents()` 自动生成 ID（`task-1`, `task-2`...），但 AI 看不到这些 ID
2. `renderSystemReminder()` 只展示内容和状态，不展示 ID：`- [ ] write_file D:\helloXUPT.java`
3. AI 只能猜测使用工具名 `"write_file"` 作为 ID，但实际 ID 是 `"task-1"`
4. `updateStatus()` 只支持精确 ID 匹配，不支持内容匹配

**修复** (2 处):
1. **`renderSystemReminder()` 展示 ID**: `- [ ] [task-1] write_file D:\helloXUPT.java`
2. **`updateStatus()` 增加内容模糊匹配 fallback**:
   ```java
   // 第一步: 精确 ID 匹配
   if (id.equals(item.getId())) → 命中
   // 第二步: 内容包含匹配 (fallback)
   if (item.getContent().contains(id)) → 命中
   ```

**涉及文件**: `TodoTracker.java`

---

## 四、最终数据流

```
Turn N: "帮我写 helloXUPT.java"
  │
  ├─ Phase 1: Plan
  │    ├─ prepareMessages:
  │    │    ├─ SystemMessage (角色 + 规范 + Skills)
  │    │    ├─ History (经 micro_compact + 滑动窗口/LLM摘要)
  │    │    └─ UserMessage("用户输入" + Current Context)
  │    │         ├─ 🎯 当前目标: (上一轮简洁摘要 或 "等待用户输入任务目标")
  │    │         ├─ 🔧 可用工具: (从 ToolRegistry 动态获取)
  │    │         └─ ⚡ 上一轮执行结果: (完整保留 或 "首次对话")
  │    └─ AI 返回: assistantText + toolCalls
  │
  ├─ history += [assistant] AI 完整回复
  ├─ history += [system]  <plan>方案计划 + 工具调用</plan>
  ├─ history += [system]  TODO 列表 (自动生成或显式声明)
  │
  ├─ Phase 2+3: Execute + ReAct
  │    ├─ 逐个执行工具
  │    ├─ 每个结果 → history += [system] 工具结果
  │    └─ AI 可调用 manage_todo(action="complete", id="...") 更新进度
  │
  └─ saveCurrentContext:
       ├─ lastPlanSummary  = "好的，我来帮你创建一个 helloXUPT 的 Java 程序！"  (≤100字)
       └─ lastToolResults  = "write_file: ✅ File written...\n"
```
