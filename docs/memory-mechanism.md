# ThinkingCoding 记忆机制详解

## 概述

ThinkingCoding 的记忆机制采用**多层分级**架构，从持久化存储、上下文管理、工作记忆到外部记忆服务，共同保证 Agent 在多轮对话中的连贯性和任务追踪能力。

---

## 1. 会话持久化 — `SessionService`

**源码：** `src/main/java/com/thinkingcoding/service/SessionService.java`

整个记忆体系的基础层。所有对话历史以 JSON 文件形式持久化到 `sessions/` 目录。

### 数据模型

```
SessionData
├── sessionId       (UUID)
├── title           (会话标题)
├── modelName       (使用的模型)
├── createdTime     (创建时间)
├── lastAccessTime  (最后访问时间)
└── messages[]      (ChatMessage 列表)
    ├── id          (UUID)
    ├── role        (user | assistant | system)
    ├── content     (消息内容)
    ├── timestamp   (时间戳)
    └── sessionId   (所属会话)
```

### 核心流程

```
用户输入 → AgentOrchestrator.onUserInput()
    ├── 创建 TurnContext(sessionId, modelName, history, turnIndex)
    ├── 用户消息追加到 history
    ├── Plan → Execute+ReAct → Steering
    │   └── 工具执行结果以 "system" role 写回 history
    ├── saveCurrentContext() → 更新 ContextManager 的 lastPlanSummary / lastToolResults
    └── sessionGateway.save(sessionId, history)  ← 持久化到磁盘
```

### 关键 API

| 方法 | 作用 |
|------|------|
| `createNewSession(title, modelName)` | 创建新会话，生成 UUID |
| `saveSession(sessionId, messages)` | 将会话历史序列化为 JSON 存入 `sessions/` |
| `loadSession(sessionId)` | 从磁盘加载会话历史（过滤空消息） |
| `deleteSession(sessionId)` | 删除会话（内存 + 磁盘） |
| `listSessions()` | 列出所有可用会话 |

---

## 2. 上下文管理 — `ContextManager`

**源码：** `src/main/java/com/thinkingcoding/service/ContextManager.java`

核心记忆管理器，在每次 AI 调用前对对话历史进行**压缩和截断**，防止 Token 超限。

### 三种压缩策略

| 策略 | 枚举值 | 机制 |
|------|--------|------|
| **滑动窗口** | `SLIDING_WINDOW` | 保留最近 N 轮对话（默认 10 轮），丢弃更早的消息 |
| **Token 控制** | `TOKEN_BASED` | 当历史 Token 数超过阈值时，调用 LLM 生成摘要替换早期历史 |
| **混合策略** | `HYBRID` | 先应用滑动窗口，再应用 Token 控制 |

### Token 压缩流程（`TOKEN_BASED` 策略）

```
完整历史 (fullHistory)
    │
    ▼
recordTokenUsage() → 记录每次 AI 调用的 promptTokens / completionTokens / totalTokens
    │
    ▼
shouldCompressHistory()
    │  判断条件: lastHistoryTokens >= (maxContextTokens - maxOutputTokens)
    │
    ├── false → 不压缩，直接使用
    │
    └── true → 触发压缩
        │
        ├── 1. 切分历史: toSummarize(早期) + tailMessages(最后2条)
        ├── 2. 构建摘要 prompt，包含:
        │       - What was accomplished
        │       - Current state
        │       - Key decisions made
        ├── 3. 调用 LLM 生成摘要
        ├── 4. 清空 fullHistory，写入:
        │       [Conversation compressed.] + 摘要
        │       + tailMessages
        └── 5. resetTokenUsage()
```

### 工具结果微压缩 (`micro_compact`)

在 Agent 多次调用工具的对话中，对**上一轮用户消息之前**的工具调用结果进行截断：

```
原始工具结果 (2000+ 字符)
    ↓
file_manager / command_executor / grep_search:
    保留前后 200 字符 + 中间省略标记
其他工具:
    保留前 500 字符 + 截断标记
```

### Current Context 注入

每次 AI 调用时，`buildCurrentContext()` 在用户消息末尾追加当前上下文：

```markdown
## 📋 当前上下文

### 🎯 当前目标
(上一轮 Plan 的任务摘要)

### 🔧 可用工具
- tool1: 描述
- tool2: 描述

### ⚡ 上一轮执行结果
✅ tool1 执行成功: ...
❌ tool2 执行失败: ...
```

**数据来源：** `AgentOrchestrator.saveCurrentContext()` 在每轮执行结束后更新。

### 项目上下文注入

`buildProjectContextMessage()` 构建固定的系统消息，包含：
- 角色定位与行为规范（中文回答、主动思考、提供选项）
- 智能工作流程（TODO 指南针、文件操作规范）
- 可用 Skill 列表及其调用方式
- 禁止事项

此消息作为 **SystemMessage** 在所有对话中注入，**永不截断**。

---

## 3. Turn 上下文 — `TurnContext`

**源码：** `src/main/java/com/thinkingcoding/agentloop/v2/model/TurnContext.java`

单回合（用户一次输入 → AI 一次完整响应 + 工具执行）的状态容器。

```
TurnContext
├── sessionId        (关联同一会话的所有回合)
├── modelName        (当前使用的 AI 模型)
├── history          (完整对话历史引用)
├── turnIndex        (回合序号，从 0 开始自增)
├── runId            (每次执行的唯一 ID: run_<timestamp>_<turnIndex>)
└── todoTracker      (TODO 跟踪器)
```

`TurnContext` 在每次 `AgentOrchestrator.onUserInput()` 调用时创建，贯穿 Plan → Execute+ReAct → Steering 三个阶段。

---

## 4. TODO 跟踪 — `TodoTracker`

**源码：** `src/main/java/com/thinkingcoding/agentloop/v2/model/TodoTracker.java`

任务级别的**工作记忆**，在 Agent 执行复杂多步骤任务时维护进度。

### 数据模型

```
TodoTracker
├── items[]              (TodoItem 列表，线程安全)
│   ├── id               (task-1, task-2, ...)
│   ├── content          (任务描述)
│   └── status           (PENDING | IN_PROGRESS | DONE)
├── counter              (AtomicInteger ID 生成器)
└── completedContents    (已完成任务缓存，避免重复完成)
```

### 关键行为

- **去重：** 完成后将内容存入 `completedContents` Set，再次添加相同内容时自动标记为 `DONE`
- **模糊匹配：** `updateStatus(id)` 先精确 ID 匹配，失败后按内容包含匹配（兼容 AI 使用工具名/描述作为 ID）
- **System Reminder 渲染：** 在 ReAct 循环中通过 `<system-reminder>` 标签注入 TODO 列表，引导 AI 关注当前任务

### 生命周期

```
Plan 阶段 → refreshTodoFromPlan()
    ├── 如果 AI 显式声明了 manage_todo(add) → 提取为 TODO
    └── 否则 → 从工具调用列表中自动生成 TODO

Execute+ReAct 阶段 → 每个 continuation request 前注入
    └── turn.getTodoTracker().renderSystemReminder()
```

---

## 5. ReAct 循环中的观察记忆

**源码：** `src/main/java/com/thinkingcoding/agentloop/v2/orchestrator/ReActDriver.java`

工具执行结果作为 **observation** 写回对话历史，构成 Agent 的**即时工作记忆**。

### 防重复记忆

```java
// 生成工具调用签名: toolName_param1=val1;param2=val2;...
String toolSignature = generateToolSignature(currentToolCall);

// 检测重复 → 终止当前回合，避免死循环
if (toolCallAttempts.get(toolSignature) > 1) {
    // 注入 system reminder 并取消回合
}
```

### 失败重试记忆

```
连续失败 < 3 次:
    → 触发 re-plan，将失败信息注入 PlanRequest prompt
    → 清空工具调用队列，重新规划

连续失败 >= 3 次:
    → 构建失败摘要 <system-reminder>
    → 触发 fallback plan
```

### 续跑规划（ReAct 核心）

每次工具执行队列清空后，自动触发续跑：

```
"基于以上工具执行结果，请继续完成任务。如果任务已完成，请总结结果。"
+ todoTracker.renderSystemReminder()
→ 触发 Planner 生成新的 PlanResult（含新的工具调用）
```

---

## 6. 消息准备流程 — `LangChainService.prepareMessages()`

**源码：** `src/main/java/com/thinkingcoding/service/LangChainService.java:537-567`

每次 AI 调用的消息构建流程，体现了记忆的**分层注入**：

```
prepareMessages(input, history)
    │
    ├── Part 1: SystemMessage
    │   └── contextManager.buildProjectContextMessage()
    │       ├── 角色定位 + 行为规范
    │       ├── 当前工作目录
    │       ├── 智能工作流程
    │       └── 可用 Skill 列表
    │
    ├── Part 2: History Messages (经 ContextManager 管理)
    │   └── contextManager.getContextForAI(history)
    │       ├── micro_compact() — 工具结果截断
    │       └── applyTokenLimit() / applySlidingWindow() — 历史压缩
    │
    └── Part 3: Augmented UserMessage
        └── input + contextManager.buildCurrentContext(tools)
            ├── 🎯 当前目标
            ├── 🔧 可用工具
            └── ⚡ 上一轮执行结果
```

---

## 7. MCP 外部记忆服务

**源码：** `src/main/java/com/thinkingcoding/mcp/MCPToolManager.java:120-126`

通过 MCP (Model Context Protocol) 连接外部记忆服务，提供**跨会话持久记忆**。

### 配置方式

```java
case "memory":
    config.put("command", List.of(getDefaultNpxCommand()));
    config.put("args", Arrays.asList("-y", "@modelcontextprotocol/server-memory"));
```

### 与其他记忆的关系

| 记忆类型 | 作用域 | 持久化 | 用途 |
|---------|--------|--------|------|
| Session 历史 | 单会话 | JSON 文件 | 保持对话连贯性 |
| ContextManager 压缩 | 单会话 | 内存 | Token 窗口管理 |
| TodoTracker | 单会话 | 内存 | 任务进度追踪 |
| Current Context | 跨回合(单会话) | 内存 | 上下文注入 |
| MCP Memory | **跨会话** | MCP 服务端 | 用户偏好、长期记忆 |

---

## 8. Skill 上下文

**源码：** `src/main/java/com/thinkingcoding/skill/SkillContextLoader.java`

每个 Skill 可携带自己的上下文信息，分为两级：

| 级别 | 来源 | 注入位置 |
|------|------|----------|
| **简要上下文** (`briefContext`) | config.yaml 中的 `briefContext` 字段 | 系统提示词中的 Skill 列表 |
| **完整上下文** (`fullContext`) | config.yaml 中的 `fullContext` 或 `fullContextPath` 指定的文件 | Skill 执行时传入 `SkillExecutionContext` |

---

## 9. Agent 配置中的记忆相关参数

**源码：** `src/main/java/com/thinkingcoding/agentloop/v2/orchestrator/AgentConfig.java`

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `maxReActStepsPerTurn` | 50 | ReAct 循环最大迭代步数（防止死循环） |
| `maxToolCallsPerPlan` | 30 | 单轮最大实际执行工具数 |
| `autoApproveDefault` | false | 是否默认自动批准工具调用 |
| `steeringMode` | STRICT | 工具确认模式 |

---

## 10. 记忆机制全景流程图

```
┌─────────────────────────────────────────────────────────────┐
│                     AgentOrchestrator                        │
│                                                              │
│  onUserInput(input)                                          │
│    │                                                         │
│    ├── TurnContext(sessionId, history, todoTracker)          │
│    │                                                         │
│    ├── [1] Plan 阶段                                         │
│    │   └── LangChainPlanner.plan()                           │
│    │       └── LangChainService.streamingChat()              │
│    │           └── prepareMessages(input, history)           │
│    │               ├── SystemMessage (角色+Skill)              │
│    │               ├── History (经 ContextManager 压缩)       │
│    │               └── UserMessage + CurrentContext           │
│    │                                                         │
│    ├── [2] Execute+ReAct 阶段                                │
│    │   └── ReActDriver.run()                                 │
│    │       ├── 工具执行 → observation 写回 history            │
│    │       ├── 防重复签名检测                                 │
│    │       ├── 失败重试/fallback                              │
│    │       ├── TODO 跟踪注入                                  │
│    │       └── 续跑规划 (continuation)                        │
│    │                                                         │
│    ├── saveCurrentContext()                                   │
│    │   ├── ContextManager.setLastPlanSummary()               │
│    │   └── ContextManager.setLastToolResults()               │
│    │                                                         │
│    └── sessionGateway.save() → sessions/<uuid>.json          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 11. 记忆膨胀的对策

项目通过**三层防线**防止记忆膨胀导致 Token 超限：

| 防线 | 触发条件 | 机制 |
|------|---------|------|
| **micro_compact** | 每次 AI 调用 | 对非最新轮的工具结果截断至 200-500 字符 |
| **Token 监控** | 每次 AI 响应后 | `recordTokenUsage()` 记录 prompt/completion/total tokens |
| **历史压缩** | `lastHistoryTokens >= threshold` | 调用 LLM 对早期历史生成摘要，替换原始消息 |
| **滑动窗口** | 每次 AI 调用 (SLIDING_WINDOW 模式) | 只保留最近 N 轮对话 |

---

## 12. 实战示例：一个完整的多轮交互

以下通过一个具体场景 —— 用户分两次输入完成 "创建用户管理模块并编写测试" —— 追踪记忆在每一层的流转。

### 前置条件

```
会话ID: a1b2c3d4-...（已通过 /session create 创建）
模型: deepseek-v3
AgentConfig: maxReActSteps=50, maxToolCalls=30, autoApprove=false
ContextManager: strategy=TOKEN_BASED, maxContextTokens=7000, maxOutputTokens=4096
历史: 空（新会话首次对话）
```

---

### 第 1 轮：用户输入 "帮我创建一个 UserService.java，包含 getUserById 和 createUser 两个方法"

#### 步骤 1：TurnContext 创建

```
AgentOrchestrator.onUserInput("帮我创建一个 UserService.java...")

TurnContext {
    sessionId   = "a1b2c3d4-..."
    modelName   = "deepseek-v3"
    history     = []                    ← 引用 AgentOrchestrator.history
    turnIndex   = 0                     ← 第 0 轮
    runId       = "run_1715678900_0"
    todoTracker = new TodoTracker()     ← 空列表
}
```

用户消息立即追加到 history：

```
history[0] = ChatMessage(role="user", content="帮我创建一个 UserService.java，包含 getUserById 和 createUser 两个方法")
```

#### 步骤 2：Plan 阶段 — prepareMessages()

`LangChainService.prepareMessages()` 构建发给 AI 的消息：

```
Part 1 — SystemMessage (项目上下文，约 2500 tokens):
  "## 重要指令\n! 你必须始终使用中文回答..."
  "## 你的角色定位\n你是一位资深的编程助手..."
  "## 当前工作环境\n工作目录: /mnt/d/ThinkingCoding"
  "## 智能工作流程..." (TODO指南针、文件操作规范)
  "## 可用 Skill 工具\n### autotest\n- 描述: 自动生成测试代码\n..." ← SkillContextLoader 注入

Part 2 — History Messages (经 ContextManager 管理):
  无（当前 history 只有一条 user 消息，且是当前轮最新消息）

Part 3 — Augmented UserMessage:
  "帮我创建一个 UserService.java..." +
  "\n\n---\n## 📋 当前上下文\n" +
  "### 🎯 当前目标\n等待用户输入任务目标\n"            ← lastPlanSummary 为空
  "### 🔧 可用工具\n- file_manager: ...\n- command_executor: ...\n..." ← 全部工具列表
  "### ⚡ 上一轮执行结果\n（首次对话，尚未执行任何工具）\n"   ← lastToolResults 为空
```

#### 步骤 3：AI 返回 PlanResult

AI 分析用户请求后生成规划。假设它返回了工具调用：

```
assistantText: "好的，我来创建 UserService.java。这个类将包含：
  1. getUserById(Long id) - 根据ID查询用户
  2. createUser(User user) - 创建新用户
  首先让我检查现有的项目结构。"

toolCalls:
  [0] toolName="file_manager", parameters={command="list", path="src/main/java"}
  [1] toolName="file_manager", parameters={command="write", path="src/main/java/.../UserService.java", content="..."}
```

Assistant 文本以 `assistant` role 记入 history：

```
history[1] = ChatMessage(role="assistant", content="好的，我来创建 UserService.java...")
```

用户确认计划（`InteractiveToolConfirmationPolicy` 在非 auto-approve 模式下要求用户输入 y/n）。

#### 步骤 4：TODO 生成 — refreshTodoFromPlan()

`AgentOrchestrator.refreshTodoFromPlan()` 从 PlanResult 提取 TODO：

```
没有显式的 manage_todo 调用 → 自动从工具列表生成:

TodoTracker.items:
  [task-1] [ ] file_manager list src/main/java          (PENDING)
  [task-2] [ ] file_manager write .../UserService.java  (PENDING)
```

TODO 以 `<system-reminder>` 格式注入 history：

```
history[2] = ChatMessage(role="system", content=
  "<system-reminder>\n你当前的 TODO：\n- [ ] [task-1] file_manager list src/main/java\n...\n</system-reminder>")
```

总结计划也记入 history：

```
history[3] = ChatMessage(role="system", content=
  "<plan>\n方案计划:\n好的，我来创建...\n工具调用:\n-1 file_manager ...\n...</plan>")
```

#### 步骤 5：Execute+ReAct 阶段

`ReActDriver.run()` 进入主循环。第 1 个工具调用 `file_manager list`：

```
1. Steering: InteractiveToolConfirmationPolicy.decide()
       → 非 auto-approve → legacyConfirmation.askConfirmationWithOptions()
       → 用户选择 "CREATE_AND_RUN" → 返回 ToolDecision.EXECUTE_AND_FOLLOWUP

2. DefaultToolExecutionEngine.execute():
       → ToolResolver 解析到 file_manager 工具
       → 执行 command="list", path="src/main/java"
       → 返回: ToolExecutionOutcome(executed=true, result=成功)
```

工具结果以 `system` role 写回 history（**ReAct 观察记忆**）：

```
history[4] = ChatMessage(role="system", content=
  "Tool 'file_manager' executed successfully with command=list.
   Found: com/thinkingcoding/service/UserService.java\n...")
```

同时防重复签名被记录：

```
executedToolSignatures: {"file_manager_command=list;path=src/main/java;"}
```

第 2 个工具调用 `file_manager write`，同样经过 Steering → 执行 → 观察写入：

```
history[5] = ChatMessage(role="system", content=
  "Tool 'file_manager' executed successfully with command=write.
   File written: src/main/java/.../UserService.java")
```

队列清空后，`ReActDriver` 触发续跑规划：

```
continuationPrompt = "基于以上工具执行结果，请继续完成任务。如果任务已完成，请总结结果。"
  + "\n\n<system-reminder>\n你当前的 TODO：\n- [ ] [task-1] file_manager list...\n- [ ] [task-2] file_manager write...\n</system-reminder>"
```

续跑 Plan 返回（假设 AI 认为任务基本完成，输出总结）：

```
finalAssistantText = "UserService.java 已创建完成..."
toolCalls = []  ← 无新工具调用 → 退出循环
```

#### 步骤 6：saveCurrentContext() — 为下一轮准备记忆

```
ContextManager.setLastPlanSummary("好的，我来创建 UserService.java。这个类将包含...")
ContextManager.setLastToolResults(
  "**file_manager**: ✅ list 执行成功\n...\n
   **file_manager**: ✅ write 执行成功\n...")
```

#### 步骤 7：持久化

```
sessionGateway.save(sessionId, history)
  → SessionService.saveSession()
  → sessions/a1b2c3d4-....json
```

**此时 history 共 6 条消息：**

```
[0] user:     "帮我创建一个 UserService.java..."
[1] assistant: "好的，我来创建 UserService.java..."
[2] system:    <system-reminder> TODO 列表
[3] system:    <plan> 计划摘要
[4] system:    Tool 'file_manager' executed successfully... (list)
[5] system:    Tool 'file_manager' executed successfully... (write)
```

---

### 第 2 轮：用户输入 "帮我为 UserService 写单元测试"

（同一会话，5 分钟后用户继续工作）

#### 步骤 1：TurnContext 创建

```
TurnContext {
    sessionId   = "a1b2c3d4-..."   ← 同一会话
    turnIndex   = 1                  ← 递增
    runId       = "run_1715679200_1"
    todoTracker = new TodoTracker()  ← 每轮重新创建
}
```

用户消息追加到 history：

```
history[6] = ChatMessage(role="user", content="帮我为 UserService 写单元测试")
```

#### 步骤 2：Plan 阶段 — prepareMessages() 新版

这次有历史了，ContextManager 开始发挥作用：

```
Part 1 — SystemMessage:
  同上一轮

Part 2 — History Messages (经 ContextManager.getContextForAI()):
  
  ① micro_compact(): 对 history[0]~[5] 中，在"最后一个 user 消息之前"的工具结果进行截断
     - history[0](user) 是最后一个 user 消息 → 不压缩
     - history[4] 是 tool result，位于 history[0] 之后 → 不压缩
     - history[5] 是 tool result，位于 history[0] 之后 → 不压缩
     （实际上 history[0] 是上一轮的 user，但本轮新增的 user 是 history[6]，
       所以 history[4][5] 在 history[6] 之前 → 触发 micro_compact）
     
     history[4] 内容 (约 2000 字符):
       原始: "Tool 'file_manager' executed successfully...\n 列出所有文件:..." (2000+ 字符)
       压缩后: "Tool 'file_manager' executed successfully...\n 列出所有文件:...(前 200 字符)\n\n
                [... omitted 1600 chars, tool: file_manager ...]\n\n...(后 200 字符)"

  ② Token 检查:
     假设历史 messages 估算约 800 tokens，阈值 2904 (7000-4096)
     → 无需触 LLM 压缩

  ③ 返回压缩后的 6 条历史消息

Part 3 — Augmented UserMessage:
  "帮我为 UserService 写单元测试" +
  "\n\n---\n## 📋 当前上下文\n" +
  "### 🎯 当前目标\n好的，我来创建 UserService.java。这个类将包含：1. getUserById...\n"
                                                    ← 上一轮的 PlanSummary
  "### 🔧 可用工具\n...\n- autotest: 自动生成测试...\n..."
  "### ⚡ 上一轮执行结果\n
   **file_manager**: ✅ list 执行成功\n
   **file_manager**: ✅ write 执行成功...\n"        ← 上一轮的 ToolResults
```

注意 Current Context 的注入，让 AI 知道 "上轮做了什么"：

```
系统提示词里告诉 AI 可用 Skill → AI 看到 autotest skill
Current Context 显示上轮创建了 UserService → AI 知道上下文
用户说 "写单元测试" → AI 匹配到 autotest 的触发条件
```

#### 步骤 3：AI 返回 PlanResult（调用 autotest skill）

AI 在系统提示词中已经知道 autotest 的用途和调用方式，于是：

```
assistantText: "我看到上一轮已经创建了 UserService.java，现在直接调用 autotest skill 来生成测试。"

toolCalls:
  [0] toolName="autotest", parameters={source="src/main/java/.../UserService.java"}
```

History 记录：

```
history[7] = ChatMessage(role="assistant", content="我看到上一轮已经创建了...")
```

#### 步骤 4：Execute — autotest 执行

```
ToolResolver 找到 autotest (由 LazySkillToolAdapter 包装的 Skill)
→ 执行 AutoTestSkill
→ 返回: ToolExecutionOutcome(executed=true, result=成功)
```

History 写入观察：

```
history[8] = ChatMessage(role="system", content=
  "Tool 'autotest' executed successfully. Generated test file: .../UserServiceTest.java")
```

（autotest 可能需要多步执行，ReActDriver 会循环处理每个工具调用并在队列清空时触发续跑）

#### 步骤 5：saveCurrentContext() 更新

```
lastPlanSummary  = "我看到上一轮已经创建了 UserService.java，直接调用 autotest..."
lastToolResults   = "**autotest**: ✅ 测试生成成功\n..."
```

覆盖了第 1 轮的数据。

#### 步骤 6：持久化

```
history (共 9 条消息) → sessions/a1b2c3d4-....json
```

---

### 第 3 轮：用户输入 "刚才的测试还有个边界条件没覆盖，加上 null 参数检查"

此时 Token 数量逐渐增加...

#### Token 膨胀触发 LLM 压缩

```
ContextManager.recordTokenUsage():
  lastHistoryTokens = 3200  ← 超出阈值 2904

shouldCompressHistory() → true!
```

压缩流程：

```
1. 切分历史:
   toSummarize = history[0] ~ history[6]  (第 1 轮 + 第 2 轮的 user)
   tailMessages = history[7] + history[8]  (第 2 轮最后 2 条)

2. LLM 摘要 prompt:
   "Summarize this conversation for continuity. Include:
    1) What was accomplished, 2) Current state, 3) Key decisions made."
    + toSummarize 的 JSON 序列化

3. LLM 返回摘要:
   "第1轮：用户要求创建 UserService.java，包含getUserById和createUser方法。
    成功创建文件在 src/main/java/.../UserService.java。第2轮：用户要求编写测试，
    通过 autotest skill 生成了 UserServiceTest.java。当前状态：UserService和
    测试类均已创建完成。"

4. 重构 history:
   history.clear()
   history[0] = ChatMessage("user", "[Conversation compressed.]\n\n" + 摘要)
   history.addAll([history[7], history[8]])  ← 接上 tail
   history.add(newUserMessage)
```

**压缩后 history 从 9 条消息缩减为约 4 条核心记录**，总 Token 从 3200+ 回到约 800。新的规划请求在紧凑的上下文中继续，AI 依然知道之前做了什么，但早期冗余细节被 LLM 摘要替代。

---

### 记忆流转总结

```
第 1 轮                             第 2 轮                            第 3 轮
──────────────────────────────────────────────────────────────────────────────────
磁盘: sessions/a1b2c3d4-....json ──→ 同一文件追加 ──────→ 同一文件追加
                                       (SessionService)         (压缩后写回)
                                      
ContextManager:                                  
  lastPlanSummary  = null     →   "创建UserService..."  →  "调用autotest..."
  lastToolResults  = null     →   "file_manager ✅..."  →  "autotest ✅..."
  lastHistoryTokens= -1       →   800 (未超阈值)       →   3200 (触发LLM压缩)

history (内存中):
  [0] user: "创建..."        →  [micro_compact 截断]   →  压缩为 LLM 摘要
  [1] assistant: "好的..."    →  保留                   →  + tail 消息
  [2] system: TODO           →  保留                    
  [3] system: plan           →  保留                    
  [4] system: tool结果(压缩)  →  保留                    
  [5] system: tool结果(压缩)  →  保留                    
  [6]                              user: "写测试"        →  保留
  [7]                              assistant: "直接调用"  →  保留 → 在tail中
  [8]                              system: autotest结果   →  保留 → 在tail中
  [9]                                                   user: "加null检查"

注入到 AI 请求的消息:
  SystemMessage(角色+Skill+规范)    SystemMessage(角色+Skill+规范)    SystemMessage(角色+Skill+规范)
  + History(经ContextManager)       + History(经ContextManager)       + History(摘要压缩后)
  + CurrentContext(null)            + CurrentContext(上轮目标+结果)   + CurrentContext(上轮目标+结果)
                                   ─── 让AI无需被重复告知 ───       ─── Token始终可控 ───
```

