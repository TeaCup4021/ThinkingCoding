# ThinkingCoding CLI — 项目实现方案与成果总结

## 一、项目概述

**ThinkingCoding CLI** 是一个基于 Java 17 的本地智能编程助手命令行工具。通过 LangChain4j 对接 DeepSeek、Qwen 等大语言模型，在终端中提供交互式 AI 辅助编程体验。

| 项 | 详情 |
|---|---|
| 语言 / 运行时 | Java 17+ |
| 构建工具 | Maven |
| AI 框架 | LangChain4j 1.10.0 |
| 模型后端 | DeepSeek, Qwen |
| 代码规模 | ~17,770 行 Java，96+ 源文件 |

---

## 二、实现方案

### 整体思路

项目围绕 **"AI 驱动的终端编程助手"** 这一目标，采用分层架构：

```
CLI 接入层 → Agent 编排层 → 工具体系层 → 服务支撑层 → 基础设施层
```

核心流程：用户输入 → `AgentOrchestrator` 三阶段循环（**Plan → Execute+ReAct → Steering**）→ AI 调用工具完成任务 → 结果返回用户。

### Agent 编排 (V2)

每个对话轮次执行三阶段流水线：

1. **Plan** — `LangChainPlanner` 调用 AI 模型生成执行计划（文本响应 + 工具调用列表）
2. **Execute + ReAct** — `ReActDriver` 迭代执行工具调用，观察结果写回历史，失败自动重规划，重复工具签名检测防死循环（上限 50 步）
3. **Steering** — 逐工具确认策略，支持 `/auto-approve`、`/stop`、`/cancel` 运行时控制

### 工具体系

`ToolRegistry` 作为统一注册中心，管理三类工具：

- **内置工具 ×7**：文件管理、命令执行、代码执行、文本搜索、TODO 追踪、代码图查询、语义搜索
- **MCP 外部工具**：完整实现 JSON-RPC 2.0 over stdio 客户端，预置 filesystem/github/gitnexus 等 8 个服务器，支持运行时动态连接
- **Skill 技能工具**：通过 `LazySkillToolAdapter` 将工作流（如 `AutoTestSkill` 自动生成和修复测试）包装为 AI 可调用的工具

### 关键能力

- **RAG / 代码图**：JavaParser 静态分析构建符号依赖图，语义搜索基于 BM25 + 向量嵌入 + RRF 融合
- **上下文管理**：滑动窗口 + Token 计数 + LLM 摘要的多策略压缩
- **会话持久化**：JSON 文件存储，支持断点续聊
- **终端 UI**：JLine 驱动的 ANSI 色彩交互界面
- **配置驱动**：YAML 管理模型、工具、MCP、Skill 等全部配置

---

## 三、技术栈

| 技术 | 用途 |
|---|---|
| Java 17 + Maven | 运行时与构建 |
| LangChain4j 1.10.0 | AI 模型集成、工具调用 |
| Picocli 4.7.5 | CLI 参数解析 |
| JLine 3.23.0 | 终端 UI |
| Jackson 2.16.1 | JSON/YAML 序列化 |
| JavaParser 3.25.10 | Java AST 解析 |
| OkHttp 4.12.0 | HTTP 客户端 |
| JUnit 5.10.1 + Mockito 5.7.0 | 测试 |

---

## 四、成果达成

### 功能完整性

- ✅ 完整的三阶段 Agent 循环（Plan → Execute+ReAct → Steering）
- ✅ 7 个内置工具覆盖文件、命令、代码执行、搜索、待办、代码图、语义搜索
- ✅ MCP 协议完整实现，预置 8 个服务器，支持动态管理
- ✅ 可扩展 Skill 框架，内置 AutoTestSkill
- ✅ 代码图构建 + 语义搜索（BM25 + Embedding + RRF）
- ✅ 多策略上下文压缩 + Token 管理
- ✅ 会话持久化，支持断点续聊
- ✅ Picocli CLI 子命令体系（session / config / skill / mcp）
- ✅ JLine 终端 UI，色彩主题 + 组件化渲染
- ✅ 直接命令执行 + 安全过滤 + 危险操作确认
- ✅ 运行时安全机制：命令白名单、死循环检测、失败自愈

### 工程能力

- ✅ Maven 构建体系，uber-jar 一键打包
- ✅ 分层架构，接口与实现分离
- ✅ 设计模式规范应用（策略/适配器/注册表/DI）
- ✅ YAML 驱动配置

### 测试覆盖

共 12 个测试文件，覆盖 TODO 追踪、工具解析、确认策略、上下文压缩、命令翻译、MCP 序列化、语义搜索、Skill 适配器等核心模块。

### 当前阶段

- 🔄 测试以单元测试为主，缺集成/端到端测试
- 🔄 Skill 生态初期（仅 AutoTestSkill）
- 🔄 代码图/语义搜索为近期新增，持续迭代中
