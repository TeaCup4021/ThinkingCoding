# ThinkingCoding CLI

![ThinkingCoding CLI](img.png)

基于 LangChain4j 的交互式 AI 代码助手 CLI 工具，支持流式对话、工具调用、MCP 协议集成和可扩展的 Skill 工作流。

## 项目特性

- **V2 Agent 编排** — Plan → Execute + ReAct → Steering 三阶段流水线，支持自动批准和转向控制
- **流式对话** — Token-by-Token 实时输出，支持中途停止生成
- **MCP 集成** — 完整的 Model Context Protocol 客户端，支持多服务器同时连接、动态发现工具
- **Skill 工作流** — 可扩展的 Skill 模块系统，AI 可自动发现和调用（内置 AutoTest 自动测试生成）
- **代码图索引** — 基于 Java AST 解析的代码符号索引（CodeGraph），支持符号查找和依赖分析
- **工具系统** — 内置文件管理、命令执行、代码执行、文本搜索、TODO 追踪工具
- **会话管理** — 会话保存/加载/继续，JSON 格式持久化
- **上下文管理** — 滑动窗口 Token 管理，当前任务上下文注入
- **终端 UI** — JLine + ANSI 现代化终端界面，支持代码高亮
- **项目感知** — 自动检测 Maven/Gradle/NPM 项目，提供项目上下文
- **工具确认** — 交互式工具执行确认，支持 /auto-approve-on|off 切换
- **跨平台** — Windows、Linux、macOS

## 项目结构

```
ThinkingCoding/
├── src/main/java/com/thinkingcoding/
│   ├── ThinkingCodingCLI.java                # CLI 入口
│   ├── TestClient.java                       # 测试用客户端
│   ├── cli/                                  # 命令行接口 (Picocli)
│   │   ├── ThinkingCodingCommand.java         # 主命令：交互模式、单次对话、V1/V2 切换
│   │   ├── SessionCommand.java               # 会话管理子命令
│   │   ├── ConfigCommand.java                # 配置管理子命令
│   │   ├── SkillCommand.java                 # Skill 管理子命令
│   │   └── MCPCommand.java                   # MCP 管理子命令
│   ├── core/                                 # 核心组件
│   │   ├── ThinkingCodingContext.java         # DI 容器 / 服务定位器
│   │   ├── AgentLoop.java                    # Legacy Agent 循环 (V1)
│   │   ├── MessageHandler.java               # 消息处理器
│   │   ├── StreamingOutput.java              # 流式输出处理
│   │   ├── ProjectContext.java               # 项目类型检测
│   │   ├── OptionManager.java                # AI 多选项管理
│   │   ├── ToolExecutionConfirmation.java    # 工具执行确认 (Legacy)
│   │   └── DirectCommandExecutor.java        # 直接命令执行器
│   ├── agentloop/v2/                         # V2 Agent 编排器
│   │   ├── orchestrator/
│   │   │   ├── AgentOrchestrator.java         # 三阶段编排器 (Plan → Execute+ReAct → Steering)
│   │   │   ├── AgentConfig.java              # Agent 配置
│   │   │   └── ReActDriver.java              # ReAct 驱动：执行-观察-反思循环
│   │   ├── plan/
│   │   │   ├── Planner.java                  # 规划器接口
│   │   │   └── LangChainPlanner.java         # LangChain4j 实现
│   │   ├── execute/
│   │   │   ├── ToolExecutionEngine.java      # 工具执行引擎接口
│   │   │   ├── DefaultToolExecutionEngine.java
│   │   │   ├── ToolResolver.java             # 工具解析器
│   │   │   ├── ToolExecutionOutcome.java
│   │   │   └── ToolResultFormatter.java
│   │   ├── steer/
│   │   │   ├── ToolConfirmationPolicy.java   # 确认策略接口
│   │   │   ├── InteractiveToolConfirmationPolicy.java  # 交互式实现
│   │   │   ├── SteeringCommand.java          # 转向命令枚举
│   │   │   ├── SteeringController.java
│   │   │   ├── SteeringHandle.java
│   │   │   └── ToolDecision.java
│   │   ├── model/
│   │   │   ├── PlanRequest.java / PlanResult.java
│   │   │   ├── ExecuteReactResult.java
│   │   │   ├── TurnContext.java
│   │   │   └── TodoTracker.java / TodoItem.java / TodoStatus.java
│   │   └── gateway/
│   │       ├── SessionGateway.java / DefaultSessionGateway.java
│   │       └── AgentEventSink.java / DefaultAgentEventSink.java
│   ├── service/                              # 服务层
│   │   ├── AIService.java                    # AI 服务接口
│   │   ├── LangChainService.java             # LangChain4j 核心实现 (流式+工具调用)
│   │   ├── SessionService.java              # 会话持久化
│   │   ├── ContextManager.java              # 上下文窗口管理 + Token 控制
│   │   └── PerformanceMonitor.java          # 性能监控
│   ├── tools/                                # 工具集合
│   │   ├── BaseTool.java                    # 工具基类
│   │   ├── ContextAwareTool.java            # 上下文感知工具接口
│   │   ├── ToolRegistry.java                # 工具注册中心（统一注册）
│   │   ├── ToolProvider.java                # 工具提供者接口
│   │   ├── exec/
│   │   │   ├── CommandExecutorTool.java      # 系统命令执行
│   │   │   └── CodeExecutorTool.java         # 代码片段执行
│   │   ├── file/
│   │   │   └── FileManagerTool.java          # 文件管理
│   │   ├── search/
│   │   │   └── GrepSearchTool.java           # 文本搜索
│   │   ├── todo/
│   │   │   └── AgentTodoTool.java            # Agent TODO 追踪
│   │   └── rag/
│   │       └── CodeGraphTool.java            # 代码图查询工具
│   ├── mcp/                                  # MCP 协议模块
│   │   ├── MCPService.java                  # MCP 服务管理器（多服务器）
│   │   ├── MCPClient.java                   # MCP 客户端（JSON-RPC over stdio）
│   │   ├── MCPToolAdapter.java              # MCP 工具 → BaseTool 适配器
│   │   ├── MCPToolManager.java              # 预定义工具管理
│   │   └── model/
│   │       ├── MCPRequest.java / MCPResponse.java / MCPError.java
│   │       ├── MCPTool.java / InputSchema.java
│   ├── skill/                                # Skill 工作流模块
│   │   ├── Skill.java                       # Skill 接口
│   │   ├── SkillRegistry.java              # Skill 注册中心
│   │   ├── SkillFactory.java               # Skill 工厂
│   │   ├── SkillResult.java                # Skill 执行结果
│   │   ├── SkillExecutionContext.java      # Skill 执行上下文
│   │   ├── SkillContextLoader.java         # Skill 上下文加载
│   │   ├── LazySkillToolAdapter.java       # Skill → BaseTool 适配器
│   │   └── autotest/
│   │       ├── AutoTestSkill.java           # 自动测试生成 Skill
│   │       └── TestPromptBuilder.java       # 测试 Prompt 构建器
│   ├── rag/codegraph/                       # 代码图索引 (RAG)
│   │   ├── CodeGraphIndex.java             # 符号索引
│   │   ├── CodeGraphSymbol.java            # 符号数据模型
│   │   ├── JavaAstIndexer.java             # Java AST 解析索引器
│   │   ├── GitNexusCodeGraphMapper.java    # GitNexus 代码图映射
│   │   ├── SymbolKind.java / ReferenceKind.java
│   │   └── IndexOptions.java
│   ├── config/                              # 配置管理
│   │   ├── AppConfig.java                  # 应用配置模型
│   │   ├── ConfigLoader.java               # YAML 配置加载
│   │   ├── ConfigManager.java              # 配置管理器（单例）
│   │   ├── MCPConfig.java                  # MCP 配置
│   │   └── MCPServerConfig.java            # MCP 服务器配置
│   ├── model/                               # 通用数据模型
│   │   ├── ChatMessage.java / SessionData.java
│   │   ├── ToolCall.java / ToolExecution.java / ToolResult.java
│   │   └── ModelConfig.java
│   ├── ui/                                  # 终端 UI
│   │   ├── ThinkingCodingUI.java            # UI 主控制器
│   │   ├── TerminalManager.java             # 终端管理
│   │   ├── AnsiColors.java                  # ANSI 颜色
│   │   ├── component/
│   │   │   ├── ChatRenderer.java            # 聊天渲染
│   │   │   ├── InputHandler.java            # 输入处理
│   │   │   ├── ProgressIndicator.java       # 进度指示
│   │   │   ├── ToolDisplay.java             # 工具调用显示
│   │   │   └── StatusBar.java               # 状态栏
│   │   └── themes/
│   │       └── ColorScheme.java             # 颜色方案
│   └── util/                                # 工具类
│       ├── JsonUtils.java / FileUtils.java
│       ├── StreamUtils.java / ConsoleUtils.java
├── src/test/java/com/thinkingcoding/        # 测试
│   ├── agentloop/v2/                        # V2 AgentLoop 测试
│   ├── cli/ / core/ / mcp/ / service/ / skill/
├── bin/
│   ├── thinking                             # Linux/macOS 启动脚本
│   └── thinking.bat                         # Windows 启动脚本
├── config.yaml                              # 配置文件 (gitignored)
├── sessions/                                # 会话存储目录
└── pom.xml                                  # Maven 构建配置
```

## 架构概览

### V2 Agent 编排器 (当前主实现)

```
用户输入 → AgentOrchestrator.onUserInput()
              │
              ├── Phase 1: Plan
              │   LangChainPlanner 调用 AI 模型生成 PlanResult
              │   (包含 assistant 文本 + 工具调用列表)
              │
              ├── Phase 2: Execute + ReAct
              │   ReActDriver 循环执行:
              │     执行工具 → 写入观察 → 失败时重新规划
              │     检测重复工具签名防止无限循环
              │
              └── Phase 3: Steering
                  InteractiveToolConfirmationPolicy
                  支持 /stop, /cancel, /auto-approve-on|off
```

- **默认配置**: 最大 20 ReAct 步数, 每轮最大 10 个工具调用, 自动批准关闭
- **支持 V1 Legacy 回退**: `--agent-loop legacy` 切换到旧版 AgentLoop

### 核心依赖关系

- `ToolRegistry` — 统一工具注册中心，管理内置工具、MCP 适配工具、Skill 适配工具
- `LangChainService` — 从 `ToolRegistry` 构建 LangChain4j tool spec，每次 AI 调用前动态生成
- `ContextManager` — 管理对话历史窗口（滑动窗口），注入可用 Skill 到系统 prompt
- `SessionService` — 会话历史序列化为 JSON 文件存储到 `sessions/`

### Skill 系统

Skill 是实现 `Skill` 接口的工作流模块。`LazySkillToolAdapter` 将它们包装为 `BaseTool`，AI 可通过工具调用自动发现和调用。在 `config.yaml` 中配置并注册。

- `AutoTestSkill` — 内置 Skill，自动生成单元测试并根据编译/测试反馈自动修复

### MCP 集成

通过 JSON-RPC over stdio 连接外部 MCP 工具服务器。`MCPService` 管理多个 `MCPClient` 实例。每个 MCP 工具由 `MCPToolAdapter` 包装并注册到 `ToolRegistry`。

- 配置文件预连接：在 `config.yaml` 的 `mcp.servers` 中配置，启动时自动连接
- 运行时动态连接：`/mcp connect <name> <command>` 命令行按需连接

### 代码图索引 (RAG)

基于 `javaparser` 解析 Java 源码构建符号索引。`JavaAstIndexer` 遍历 AST 提取类、方法、字段，存入 `CodeGraphIndex`。`CodeGraphTool` 提供 AI 可调用的代码查询能力。

## 快速开始

### 环境要求

- Java 17+
- Maven 3.6+
- Node.js 16+ (MCP 功能需要)

### 构建

```bash
git clone https://github.com/zengxinyueooo/ThinkingCoding.git
cd ThinkingCoding
# 编辑 config.yaml，配置 API Key 和模型
mvn clean package
```

### 运行

```bash
# 交互模式 (默认 V2 AgentLoop)
./bin/thinking

# 继续上次会话
./bin/thinking -c

# 指定会话
./bin/thinking -S <session-id>

# 单次提问
./bin/thinking -p "帮我写一个 Java 工具类"

# 指定模型
./bin/thinking -m deepseek-chat

# 使用 Legacy AgentLoop
./bin/thinking --agent-loop legacy

# 启用自动批准
./bin/thinking --auto-approve

# 查看帮助
./bin/thinking help
```

Windows 使用 `.\bin\thinking.bat` 替换 `./bin/thinking`。

### 交互模式命令

```
💬 对话:
  <任意消息>        发送给 AI
  stop / 停止       停止当前生成

🔧 直接命令:
  java version      直接执行 Java 命令
  git status        直接执行 Git 命令
  /commands         列出所有支持直接执行的命令

⚡ Steering (V2):
  /auto-approve-on  开启自动批准 (跳过工具确认)
  /auto-approve-off 关闭自动批准
  /stop             停止生成
  /cancel           取消当前回合

🔌 MCP:
  /mcp list                   列出已连接的 MCP 工具
  /mcp connect <name> <cmd>  连接 MCP 服务器
  /mcp tools <t1,t2>         使用预定义工具
  /mcp disconnect <name>     断开 MCP 服务器
  /mcp predefined            显示预定义工具

❌ exit / quit  退出
```

## 配置说明 (`config.yaml`)

```yaml
# AI 模型配置
models:
  deepseek-v1:
    name: "deepseek-chat"
    baseURL: "https://api.deepseek.com/v1"
    apiKey: "your-api-key-here"
    streaming: true
    maxTokens: 4096
    temperature: 0.7

defaultModel: "deepseek-v1"

# 工具开关
tools:
  fileManager: { enabled: true, maxFileSize: 10485760, timeoutSeconds: 30 }
  commandExec: { enabled: true, maxFileSize: 10485760, timeoutSeconds: 30 }
  codeExecutor: { enabled: true, timeoutSeconds: 60, allowedLanguages: [java, python, javascript, bash] }
  search: { enabled: true, timeoutSeconds: 30 }
  codeGraph: { enabled: true }

# Skill 注册
skills:
  - name: "auto-test"
    className: "com.thinkingcoding.skill.autotest.AutoTestSkill"
    enabled: true
    description: "自动生成和修复单元测试"

# 会话
session:
  autoSave: true
  maxSessions: 100
  sessionTimeout: 86400000

# UI
ui:
  theme: "default"
  showTimestamps: true
  colorfulOutput: true

# 性能监控
performance:
  enableMonitoring: true
  logLevel: "INFO"

# MCP 服务器
mcp:
  enabled: true
  autoDiscover: true
  connectionTimeout: 30
  servers:
    - name: "filesystem"
      command: "npx"
      enabled: true
      args:
        - "-y"
        - "@modelcontextprotocol/server-filesystem"
        - "."
```

## 开发指南

### 添加新工具

继承 `BaseTool` 并在 `ThinkingCodingContext.initialize()` 中注册：

```java
public class MyTool extends BaseTool {
    public MyTool() {
        super("my_tool", "工具描述");
    }

    @Override
    public ToolResult execute(String input) {
        // 实现逻辑
        return new ToolResult("结果", true);
    }
}
```

### 添加新 Skill

实现 `Skill` 接口，在 `config.yaml` 的 `skills` 列表中注册：

```java
public class MySkill implements Skill {
    @Override
    public String getName() { return "my-skill"; }

    @Override
    public String getDescription() { return "技能描述"; }

    @Override
    public SkillResult execute(SkillExecutionContext context) {
        // 实现工作流逻辑
        return SkillResult.success("执行完成");
    }
}
```

### 构建 & 测试

```bash
mvn clean package          # 构建 uber-jar (target/thinkingcoding.jar)
mvn test                   # 运行所有测试
mvn test -Dtest=ClassName  # 运行单个测试类
mvn exec:java -Dexec.mainClass="com.thinkingcoding.ThinkingCodingCLI"  # 从源码运行
```

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 17+ |
| 构建 | Maven |
| AI 框架 | LangChain4j 1.10.0 |
| 命令行 | Picocli 4.7.5 |
| 终端 UI | JLine 3.23.0 + Jansi |
| JSON/YAML | Jackson 2.16.1 |
| HTTP | OkHttp 4.12.0 |
| 代码解析 | JavaParser 3.25.10 |
| 日志 | SLF4J 2.0.9 |
| 测试 | JUnit 5 + Mockito 5 |
| MCP 通信 | JSON-RPC over stdio |

## 许可证

MIT — 查看 [LICENSE](LICENSE) 文件了解详情。
