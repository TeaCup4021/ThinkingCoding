# AgentLoop V2: TODO 指南针与状态注入改进方案

## 1. 架构设计思路

我们的目标是在现有的 `ReAct` 闭环中引入一个**具备状态的任务池 (TodoTracker)**。
- **Plan 阶段**：系统指导大语言模型 (LLM) 在面对复杂任务时，优先使用一个内置工具（如 `manage_todo`）来构建初步的 TODO 列表。
- **Execute 阶段**：LLM 在推进任务时，调用 `manage_todo` 更新任务状态（标记进行中、完成或调整计划）。
- **Steering/ReAct 阶段**：在 `ReActDriver` 生成下一次 `PlanRequest` 续跑提示词时，系统引擎自动从 `TodoTracker` 提取当前状态，并构建 `<system-reminder>` XML 标签强制拼接在 AI 的视野末端，形成自我强化。

## 2. 具体修改步骤及代码定义

### 第一步：新增状态与数据模型 (Model Layer)
在 `com.thinkingcoding.agentloop.v2.model` 包下新建 TODO 实体类和追踪器。

1. **`TodoItem.java`**
   - 属性: `String id;`, `String content;`, `String status;` (枚举: PENDING, IN_PROGRESS, DONE)
2. **`TodoTracker.java`**
   - 内部维护一个 `List<TodoItem>`。
   - 提供方法: `addTodo()`, `updateStatus(id, status)`, `clear()`, `renderSystemReminder()`。
   - `renderSystemReminder()` 输出格式如下的字符串：
     ```xml
     <system-reminder>
     你当前的 TODO：
     - [x] 分析项目结构
     - [ ] 更新依赖版本 (IN_PROGRESS) <- 你现在应该在关注这个
     - [ ] 运行测试验证
     </system-reminder>
     ```

### 第二步：扩展会话上下文 (TurnContext)
在 `TurnContext.java` 中引入 `TodoTracker` 生命周期。
- **修改**: 在 `TurnContext` 增加 `private final TodoTracker todoTracker = new TodoTracker();` 及其 getter 方法。由于 `TurnContext` 跨越了单次 Turn 的闭环，它能完美保存这轮复杂对话的任务状态。

### 第三步：新增内置系统工具 (Tool Layer)
- **新增工具**: 创建一个硬编码在系统内的技能 `AgentTodoTool` (声明为 `manage_todo` 工具)。
- **参数**: 
  - `action`: "add" | "update" | "complete"
  - `id`: 任务ID（可选）
  - `content`: 任务内容（可选）
- **实现**: 调用该工具时，直接拿到 `TurnContext.getTodoTracker()` 并更新状态。返回字符串：`"TODO 已成功更新。"` 这样 LLM 就能感知操作成功。

### 第四步：在 ReActDriver 中注入 `<system-reminder>` (Pipeline Layer)
定位到 `ReActDriver.java`，触发 Planner 进行续跑规划的地方。

**原代码：**
```java
// 触发 Planner 进行续跑规划（ReAct 核心：基于观察结果决定下一步）
PlanRequest continuationRequest = new PlanRequest(
        turn.getSessionId(),
        turn.getModelName(),
        "基于以上工具执行结果，请继续完成任务。如果任务已完成，请总结结果。",
        turn.getHistory(),
        true
);
```

**修改为：**
```java
// 从上下文中提取当前的 TODO 状态提醒
String promptExtension = "基于以上工具执行结果，请继续完成任务。如果任务已完成，请总结结果。";
String reminder = turn.getTodoTracker().renderSystemReminder();
if (reminder != null && !reminder.isEmpty()) {
    promptExtension += "\n\n" + reminder;
}

// 触发 Planner 进行续跑规划（附加了 TODO 指南针）
PlanRequest continuationRequest = new PlanRequest(
        turn.getSessionId(),
        turn.getModelName(),
        promptExtension,
        turn.getHistory(),
        true
);
```

### 第五步：在 ContextManager 强化系统提示 (Prompt Engineering)
修改 `ContextManager.java` 中的 `buildProjectContextMessage()`，在提示词中告诉大模型这个机制的存在。

在 `## 智能工作流程（重要！）` 下方追加：

```java
context.append("### 🧭 复杂任务的结构化规划（TODO指南针）\n\n");
context.append("- 当用户分配复杂的、多步骤的编程或重构任务时，**不要立刻编写全部代码**。\n");
context.append("- **第一步必须是**调用 `manage_todo(action=\"add\", ...)` 创建任务列表。\n");
context.append("- 在接下来的每一次行动中，系统都会通过 `<system-reminder>` 提示你当前进度。\n");
context.append("- 完成一个小目标后，调用 `manage_todo(action=\"complete\", id=\"...\")` 更新进度，再进行下一步。\n\n");
```

## 3. 预期效果流程跟踪

1. **用户**：“帮我把项目里的数据库查询全部重构成 Mybatis Plus，并且写好单元测试。”
2. **LLM (Plan)**：感知到任务复杂，率先发出 3 个 `manage_todo` tool calls（设为提取配置、修改Mapper、写测试）。
3. **Engine (Execute)**：执行 `manage_todo`，后台 `TodoTracker` 被填充。
4. **ReActDriver (Mid-Turn)**：准备开启下一个步，追加 `<system-reminder>` 注入到用户的 history 末端。
5. **LLM (ReAct)**：看到 `<system-reminder>` 指示当前 "提取配置" 是 pending，于是生成读取配置文件的 `file_manager` 命令；执行完后再次调用 `manage_todo` 标注完成...
6. **循环往复**，即使经过多次迭代，依然能在上下文中保持方向。
