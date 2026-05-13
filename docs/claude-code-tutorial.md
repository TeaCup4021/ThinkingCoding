# Claude Code 完整教程

> Claude Code（简称 CC）是 Anthropic 官方推出的 CLI 工具，将 Claude 大模型直接嵌入终端，提供交互式软件工程助手能力。

---

## 目录

1. [什么是 Claude Code？](#1-什么是-claude-code)
2. [Claude Code 安装与使用](#2-claude-code-安装与使用)
3. [Claude Code 如何工作？](#3-claude-code-如何工作)
4. [Claude Code 项目初始化](#4-claude-code-项目初始化)
5. [Claude Code 项目结构](#5-claude-code-项目结构)
6. [Claude Code 交互模式](#6-claude-code-交互模式)
7. [Claude Code 操作说明](#7-claude-code-操作说明)
8. [Claude Code 基础用法](#8-claude-code-基础用法)
9. [Claude Code 上下文管理](#9-claude-code-上下文管理)
10. [Claude Code 权限配置](#10-claude-code-权限配置)

---

## 1. 什么是 Claude Code？

Claude Code 是 Anthropic 推出的**终端原生 AI 编程助手**。它不是一个简单的"代码补全"工具，而是一个能够独立完成复杂软件工程任务的**智能代理（Agent）**。

### 核心能力

- **理解代码库**：自动扫描项目结构，理解架构、依赖关系和编码风格
- **多文件编辑**：一次性跨多个文件进行修改、重构、添加功能
- **执行命令**：运行构建、测试、git 操作等 shell 命令
- **联网搜索**：获取最新文档和技术信息
- **工具调用**：支持自定义工具、MCP（模型上下文协议）扩展
- **记忆系统**：跨会话记住用户偏好和项目上下文

### 与其他工具的对比

| 特性 | Claude Code | GitHub Copilot | Cursor |
|---|---|---|---|
| 交互方式 | CLI 终端 | IDE 扩展 | IDE 独立应用 |
| 任务粒度 | 完整功能开发 | 行级代码补全 | 文件级编辑 |
| Agent 能力 | 全自主代理循环 | 无 | 部分 |
| 模型 | Claude Opus/Sonnet | 多模型 | 多模型 |
| 记忆系统 | 有 | 无 | 无 |
| MCP 扩展 | 原生支持 | 支持 | 支持 |

---

## 2. Claude Code 安装与使用

### 2.1 前置要求

- **Node.js 18+**（推荐 20 LTS）
- **Git**（用于版本控制操作）
- 操作系统：macOS、Linux、Windows（推荐 Git Bash 或 WSL）

### 2.2 安装方式

#### 方式一：npm 全局安装（推荐）

```bash
npm install -g @anthropic-ai/claude-code
```

安装完成后验证：

```bash
claude --version
# 输出示例: Claude Code v1.0.0
```

#### 方式二：一键安装脚本（macOS / Linux）

```bash
curl -sSL https://claude.ai/install | bash
```

#### 方式三：Windows 安装

在 Windows 上，建议使用 Git Bash 终端：

```bash
# 在 Git Bash 中执行
npm install -g @anthropic-ai/claude-code
```

也可以在 PowerShell 中安装，但 Git Bash 下的体验更好（路径兼容性、终端颜色等）。

### 2.3 身份验证

Claude Code 需要 API 密钥才能工作。选择以下任一种方式：

**方式一：API Key（推荐个人开发者）**

```bash
# 设置环境变量（临时，仅当前终端会话有效）
export ANTHROPIC_API_KEY="sk-ant-..."

# 或写入 shell 配置文件（永久生效）
echo 'export ANTHROPIC_API_KEY="sk-ant-..."' >> ~/.bashrc
```

**方式二：Claude 账号登录（Pro / Team / Enterprise 用户）**

```bash
claude login
```

执行后会打开浏览器进行 OAuth 授权。登录成功后自动保存凭证到 `~/.claude/credentials`。

**方式三：使用 CLAUDE_API_KEY 环境变量**

```bash
export CLAUDE_API_KEY="sk-ant-..."
```

> **优先级**：`ANTHROPIC_API_KEY` > `CLAUDE_API_KEY` > 登录凭证

### 2.4 启动 Claude Code

```bash
# 进入你的项目目录
cd /path/to/your/project

# 启动 Claude Code
claude
```

启动后进入交互式 REPL，你可以直接输入自然语言指令。

---

## 3. Claude Code 如何工作？

### 3.1 核心架构：Agent 循环

Claude Code 采用 **Agent Loop（代理循环）** 架构，每一轮用户交互经过以下阶段：

```
┌──────────────────────────────────────────────────────┐
│                    用户输入                            │
└───────────────────┬──────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────────┐
│  Step 1: 构建提示词                                    │
│  - 注入 CLAUDE.md（项目指令）                           │
│  - 注入记忆文件（memory/）                              │
│  - 注入工具列表                                        │
│  - 注入对话历史                                        │
└───────────────────┬──────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────────┐
│  Step 2: LLM 调用（调用 Anthropic API）                 │
│  - 模型思考并生成响应                                    │
│  - 可能包含文本回答和/或工具调用                          │
└───────────────────┬──────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────────┐
│  Step 3: 工具调用执行                                   │
│  - 检查权限（允许 / 拒绝 / 询问）                         │
│  - 执行工具（Bash、Read、Write、WebSearch 等）           │
│  - 捕获输出结果                                         │
└───────────────────┬──────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────────┐
│  Step 4: ReAct 反馈循环                                │
│  - 工具结果追加回对话历史                                │
│  - Claude 分析结果，决定下一步                           │
│  - 循环直到：任务完成 / 达到步数上限 / 用户中断           │
└───────────────────┬──────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────────┐
│  Step 5: 输出最终结果                                   │
│  - 呈现答案，展示代码变更的 diff                          │
└──────────────────────────────────────────────────────┘
```

### 3.2 ReAct 模式

Claude Code 使用的是 **ReAct（Reasoning + Acting）** 模式：

- **Think（思考）**：分析当前状态，决定下一步做什么
- **Act（行动）**：调用工具（读文件、搜代码、执行命令）
- **Observe（观察）**：查看工具执行结果
- **Re-Think（再思考）**：基于观察结果调整计划

这种模式让 Claude 能够处理复杂的多步骤任务，并在遇到错误时自动修正。

### 3.3 模型选择

Claude Code 支持三种模型：

| 模型 | 特点 | 适用场景 |
|---|---|---|
| **Claude Opus 4.7** | 最强推理能力 | 复杂重构、架构设计、深度分析 |
| **Claude Sonnet 4.6** | 速度快、性价比高 | 日常编码、快速修复、代码审查 |
| **Claude Haiku 4.5** | 极速、轻量 | 简单问答、格式检查、轻量任务 |

在 `settings.json` 中切换：

```json
{
  "model": "sonnet"
}
```

或使用 `/config` 命令交互式切换。

---

## 4. Claude Code 项目初始化

### 4.1 初始化命令

在项目根目录执行：

```bash
claude init
```

### 4.2 初始化过程

`claude init` 会自动完成以下操作：

1. **创建 `CLAUDE.md`**：如果不存在，生成一个包含基本模板的 CLAUDE.md 文件
2. **创建 `.claude/` 目录**：存放项目配置
3. **创建初始设置**：在 `.claude/settings.json` 中添加默认配置
4. **扫描项目类型**：自动检测 Maven、Gradle、npm、Python 等项目类型，并预填构建命令

### 4.3 CLAUDE.md 的内容结构

初始化后的 `CLAUDE.md` 通常包含：

```markdown
# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## 构建与测试

```bash
mvn clean package          # 构建项目
mvn test                   # 运行全部测试
mvn test -Dtest=ClassName  # 运行单个测试类
```

## 架构

- 入口：`main()` 创建上下文，路由到子命令
- 框架：Java 17+, Maven, Picocli
- 数据库：None（文件存储）

## 编码规范

- 使用 Lombok，启用注解处理
- 包名规范：`com.thinkingcoding.<module>`
```

### 4.4 CLAUDE.md 最佳实践

- **保持具体**：包含准确的命令、文件路径、架构说明
- **签入版本控制**：团队成员共享，统一 AI 行为
- **不包含敏感信息**：API 密钥、密码等放入环境变量
- **定期更新**：随着项目演进同步更新指令
- **从示例中学习**：参考 `claude init` 生成的模板

### 4.5 其他初始化选项

```bash
# 创建自定义 Skill 骨架
claude init --skill

# 创建 Hooks 配置
claude init --hook
```

---

## 5. Claude Code 项目结构

### 5.1 完整项目结构

```
your-project/
├── CLAUDE.md                 # 项目级 AI 指令（签入 git）
├── .claude/                  # Claude Code 配置目录
│   ├── settings.json         # 项目共享设置（签入 git）
│   ├── settings.local.json   # 个人本地设置（不签入，gitignore）
│   ├── memory/               # 持久化记忆目录
│   │   ├── MEMORY.md         # 记忆索引文件
│   │   ├── user-role.md      # 用户角色记忆
│   │   ├── project-context.md # 项目上下文记忆
│   │   └── feedback-xxx.md   # 用户反馈记忆
│   ├── skills/               # 自定义技能目录（可选）
│   │   └── my-skill/
│   │       └── SKILL.md
│   ├── hooks/                # 自定义钩子（可选）
│   │   └── pre-command.sh
│   └── worktrees/            # Git Worktree（自动创建）
├── .gitignore                # 应包含 .claude/settings.local.json
└── src/                      # 你的源代码
```

### 5.2 全局配置目录

用户级配置位于家目录：

```
~/.claude/
├── settings.json             # 全局用户设置（影响所有项目）
├── credentials               # API 凭证
├── keybindings.json          # 自定义键盘快捷键
└── scheduled_tasks.json      # 定时任务配置
```

### 5.3 配置层级与优先级

```
~/.claude/settings.json          # 第三优先级（全局默认值）
    ↓ 被覆盖
.claude/settings.json            # 第二优先级（项目共享设置）
    ↓ 被覆盖
.claude/settings.local.json      # 最高优先级（个人本地设置）
```

### 5.4 settings.json 主要配置项

```json
{
  "model": "sonnet",
  "theme": "dark",
  "verbose": false,
  "permissions": {
    "allow": [],
    "deny": []
  },
  "hooks": {},
  "env": {}
}
```

| 配置项 | 类型 | 说明 |
|---|---|---|
| `model` | string | 默认模型：`sonnet` / `opus` / `haiku` |
| `theme` | string | UI 主题：`dark` / `light` |
| `verbose` | boolean | 是否输出详细日志 |
| `permissions.allow` | string[] | 自动允许的操作列表 |
| `permissions.deny` | string[] | 自动拒绝的操作列表 |
| `hooks` | object | 生命周期钩子配置 |
| `env` | object | 环境变量注入 |
| `worktree.baseRef` | string | 工作树基准分支：`fresh` / `head` |

---

## 6. Claude Code 交互模式

### 6.1 基础 REPL 模式

启动 Claude Code 后进入**交互式 REPL**（Read-Eval-Print Loop）：

```
> 帮我在 UserService 中添加一个 findById 方法
```

Claude 会：
1. 搜索代码库找到 `UserService`
2. 分析现有代码风格
3. 生成新方法代码
4. 使用 Edit 工具插入到文件中

### 6.2 单次命令模式

不进入 REPL，直接在命令行执行一次任务：

```bash
# 提问模式（单次回答，不进入交互）
claude -p "解释这个项目的架构"

# 管道模式（接收 stdin）
cat error.log | claude -p "分析这些错误日志"

# 文件操作模式
claude -p "给 README.md 添加安装说明"
```

### 6.3 批量处理模式（非交互）

```bash
# 直接执行任务并退出
claude -p "将所有 TypeScript 文件中的 var 替换为 const" --no-interactive
```

### 6.4 斜杠命令

在 REPL 中以 `/` 开头输入特殊命令：

| 命令 | 功能 | 示例 |
|---|---|---|
| `/help` | 显示所有可用命令 | `/help` |
| `/clear` | 清除当前对话历史 | `/clear` |
| `/compact` | 手动压缩上下文 | `/compact` |
| `/config` | 交互式配置（模型、主题、权限） | `/config` |
| `/cost` | 查看当前会话 token 用量和费用 | `/cost` |
| `/doctor` | 诊断并修复常见问题 | `/doctor` |
| `/init` | 重新初始化项目配置 | `/init` |
| `/review` | 审查代码变更（git diff） | `/review` |
| `/status` | 显示当前会话元数据 | `/status` |
| `/stop` | 停止当前工具执行 | `/stop` |
| `/feedback` | 向 Anthropic 提交反馈 | `/feedback` |
| `/memory` | 管理持久记忆 | `/memory list` |
| `/mcp` | 管理 MCP 连接 | `/mcp connect <server>` |
| `/auto-approve-on` | 开启自动批准所有操作 | `/auto-approve-on` |
| `/auto-approve-off` | 关闭自动批准 | `/auto-approve-off` |

### 6.5 快捷键

| 快捷键 | 功能 |
|---|---|
| `Ctrl + C` | 中断当前操作 |
| `Ctrl + D` | 退出 Claude Code |
| `Enter` | 发送消息（单行输入） |
| `Shift + Enter` | 换行（多行输入模式） |

> 快捷键可通过 `~/.claude/keybindings.json` 自定义。

---

## 7. Claude Code 操作说明

### 7.1 全面操作分类

Claude Code 的操作可以分为以下几类：

#### 文件操作（无需确认）

| 操作 | 说明 | 示例提示 |
|---|---|---|
| 读取文件 | 读取任意文件内容 | "显示 UserService.java 的内容" |
| 搜索代码 | 按模式搜索文件 | "找到所有包含 @RestController 的 Java 文件" |
| 搜索内容 | 按内容搜索（grep） | "搜索项目中所有 TODO 注释" |
| 列出文件 | 浏览目录结构 | "列出 src/main/java 下的包结构" |

#### 编辑操作（需确认或配置权限）

| 操作 | 说明 | 示例提示 |
|---|---|---|
| 修改文件 | 精确替换文件中的内容 | "把 UserService 的 findById 返回类型改为 Optional" |
| 新建文件 | 创建新代码文件 | "在 models/ 下创建一个 UserDTO 类" |
| 重写文件 | 完全替换文件内容 | "重写这个 Controller，改为 REST 风格" |

#### 命令执行（需确认或配置权限）

| 操作 | 说明 | 示例提示 |
|---|---|---|
| 构建项目 | 运行 Maven/Gradle/npm 构建 | "运行完整构建，看看有没有编译错误" |
| 运行测试 | 执行测试套件 | "运行 UserServiceTest 并分析失败原因" |
| Git 操作 | 查看状态、提交、创建分支 | "查看当前 git 状态并帮我写提交消息" |
| 安装依赖 | 添加新的依赖包 | "在 pom.xml 中添加 Spring Security 依赖" |

#### 外部信息获取

| 操作 | 说明 | 示例提示 |
|---|---|---|
| 联网搜索 | 搜索最新文档 | "查一下 Spring Boot 3.4 的新特性" |
| 获取网页 | 抓取并分析网页内容 | "看这个 API 文档页面，帮我写对应的调用代码" |
| GitHub 操作 | 创建 PR、查看 Issue | "基于当前分支创建一个 PR" |

### 7.2 操作确认机制

当 Claude Code 需要执行"高风险"操作时（如写文件、执行命令），会弹出确认提示：

```
──────────────────────────────────────────────────────────
  Edit  UserService.java
──────────────────────────────────────────────────────────
  修改文件: src/main/java/.../UserService.java

  + public Optional<User> findById(Long id) {
  +     return userRepository.findById(id);
  + }

  按 Enter 批准  |  按 Esc 拒绝  |  输入 / 执行命令
──────────────────────────────────────────────────────────
```

你可以：
- **Enter** — 批准执行
- **Esc** — 拒绝操作
- **记住决定** — 将此次选择持久保存到 `settings.local.json`

### 7.3 模式切换

在交互过程中可以使用以下方式切换行为模式：

```bash
# 开启自动批准（所有操作不再需要确认）
/auto-approve-on

# 关闭自动批准（恢复确认机制）
/auto-approve-off

# 停止当前正在执行的操作
/stop

# 切换模型
/config    # 然后选择 Change model
```

---

## 8. Claude Code 基础用法

### 8.1 代码理解与探索

```bash
# 解释代码
"解释 ThinkingCodingCLI 这个类的职责"

# 分析调用链
"从 main() 方法开始，追踪整个启动流程"

# 查找功能位置
"处理登录请求的代码在哪个文件里？"

# 理解架构
"这个项目用了哪些设计模式？画出 Mermaid 图"
```

### 8.2 代码生成

```bash
# 生成新类
"在 com.thinkingcoding.model 包下创建一个 User 实体类，包含 id, name, email 字段"

# 生成完整功能
"为 User 实体生成 Controller、Service、Repository 三层代码"

# 生成测试
"为 UserService 的 createUser 方法写单元测试，覆盖正常和异常场景"
```

### 8.3 代码修改与重构

```bash
# 重命名（安全重构）
"把 UserDTO 的 getFullName() 改名为 getName()，同时更新所有调用方"

# 修改逻辑
"在 createUser 之前加一个邮箱格式校验"

# 代码风格统一
"把这个包下所有异常处理改为统一使用 BusinessException"
```

### 8.4 调试与修复

```bash
# 错误分析
# 直接将错误信息粘贴进去
"运行 mvn test 后 UserServiceTest 报这个错：
   NullPointerException at UserService.java:42
   帮我分析并修复"

# 修复编译错误
"修复当前所有的编译错误"
```

### 8.5 Git 工作流

```bash
# 查看变更
"查看当前有哪些未提交的修改"

# 生成提交（Claude 会分析 diff 并生成符合项目风格的提交消息）
"帮我写一个提交"

# 创建 PR
"基于当前分支创建一个 PR，目标分支是 main"

# 代码审查
/review     # 审查当前变更
```

### 8.6 使用代理（Agent）完成复杂任务

Claude Code 可以自动拆分和并行处理复杂任务：

```bash
# 触发代理模式的任务
"重构认证模块，改用 JWT 方案"
# Claude 会自动：调研 -> 设计 -> 生成代码 -> 测试 -> 修复

# 多文件改动
"把所有 Controller 的统一返回格式改为 Result<T>"
# Claude 会自动逐个处理每个 Controller 文件
```

### 8.7 实用技巧

```bash
# 1. 拖入文件
# 直接将文件从文件管理器拖入终端，Claude 会读取该文件

# 2. 粘贴错误日志
# 直接把完整的错误日志/堆栈粘贴进去

# 3. 引用历史
# 可以引用之前的对话结果
"基于刚才生成的 UserDTO，帮我写对应的 Converter"

# 4. 使用 /memory
# 保存常用的项目信息
/memory add "测试数据库连接是 jdbc:h2:mem:testdb"
```

---

## 9. Claude Code 上下文管理

### 9.1 什么是上下文？

Claude Code 的"上下文"指的是每次 API 调用时发送给模型的所有信息，包括：

- 系统指令（CLAUDE.md 内容）
- 对话历史记录
- 记忆文件（memory/）
- 可用工具列表及定义
- 当前项目信息

所有上下文都消耗 **token**，而 token 直接影响费用和响应质量。

### 9.2 滑动窗口机制

Claude Code 使用**滑动窗口（Sliding Window）**策略管理上下文：

```
┌──────────────────────────────────────────────────────┐
│                    完整对话历史                          │
│  ┌──────────────────────────────────────────────────┐ │
│  │  压缩区（早期轮次）   │  活跃区（最近 N 轮）        │ │
│  │  关键决策摘要       │  完整对话保留              │ │
│  │  生成文件列表       │  工具调用详情              │ │
│  │  发现的问题汇总     │  当前任务上下文            │ │
│  └──────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

- **活跃区**：最近 5-10 轮对话保持完整细节
- **压缩区**：较早的对话被自动压缩为摘要
- **系统区**：CLAUDE.md 和工具定义始终保留在窗口内

### 9.3 上下文压缩

当上下文接近 token 上限时，Claude Code 会**自动压缩**（Compaction）：

1. 分析早期对话，提取关键信息（决策、文件、问题）
2. 将冗长的旧消息替换为简短摘要
3. 近期的完整对话保持不变
4. 释放出的 token 空间用于继续对话

**手动触发压缩：**

```bash
/compact
```

当对话变得很长时（例如超过 30 轮），建议手动压缩来提升响应速度和降低成本。

### 9.4 成本查看

```bash
# 查看当前会话的 token 用量和预估费用
/cost
```

输出示例：

```
─────────────────────────────────────
  费用
─────────────────────────────────────
  输入 tokens:     142,500
  输出 tokens:      18,200
  总计 tokens:     160,700

  预估费用:         $1.85 (USD)
─────────────────────────────────────
```

### 9.5 记忆系统

记忆（Memory）是与 CLAUDE.md 互补的持久化信息存储机制：

| 类型 | 用途 | 示例 |
|---|---|---|
| **User（用户）** | 用户偏好、角色、技能水平 | "用户是资深后端工程师，熟悉 Java 生态" |
| **Project（项目）** | 项目相关的持久信息 | "重构认证模块截止日期 2026-06-01" |
| **Feedback（反馈）** | 用户明确的偏好和纠正 | "不要修改 lock 文件，保持依赖版本不变" |
| **Reference（参考）** | 外部资源指针 | "pipeline 相关 bug 在 Linear 项目 INGEST 中追踪" |

**管理记忆：**

```bash
# 列出所有记忆
/memory list

# 添加记忆
/memory add "用户偏好：始终使用 Java 17 Record 而非 Lombok"

# 查看记忆详情
/memory show <记忆名称>

# 删除记忆
/memory delete <记忆名称>
```

> **CLAUDE.md vs Memory**：CLAUDE.md 是签入 git 的、项目级的、静态的指令文件。Memory 是本地存储的、会话间持久的、动态变化的信息。两者互补使用。

### 9.6 上下文管理最佳实践

1. **写好 CLAUDE.md**：将固定的项目指令写入 CLAUDE.md，不需要在对话中反复说明
2. **善用记忆**：个人偏好、项目阶段性信息写入 Memory
3. **及时压缩**：对话超过 20-30 轮后主动 `/compact`
4. **避免大文件读取**：不要一次性读取上千行代码，可以分段读取
5. **清除无用对话**：话题转换时用 `/clear` 重置上下文
6. **管道模式复用**：单次任务用 `claude -p` 直接执行，不进入长对话

---

## 10. Claude Code 权限配置

### 10.1 权限模型概述

Claude Code 的权限系统控制 AI 可以执行哪些操作，有三层策略：

| 策略 | 含义 | 使用场景 |
|---|---|---|
| **allow（允许）** | 自动执行，不提示 | 读文件、运行测试等安全操作 |
| **deny（拒绝）** | 拒绝执行，不提示 | 危险操作如 `rm -rf` |
| **ask（询问）** | 每次弹出确认框 | 默认策略（未匹配 allow/deny 时） |

### 10.2 权限格式

权限条目使用 `工具名(参数模式)` 格式：

```
工具名(参数匹配模式)
```

支持的工具有：

| 工具 | 说明 |
|---|---|
| `Bash` | Shell 命令执行 |
| `Read` | 文件读取 |
| `Write` | 文件写入 |
| `Edit` | 文件编辑 |
| `Glob` | 文件通配搜索 |
| `Grep` | 内容搜索 |
| `WebFetch` | 网页内容抓取 |
| `WebSearch` | 联网搜索 |

### 10.3 配置权限

在 `settings.json` 中配置权限：

```json
{
  "permissions": {
    "allow": [
      "Bash(npm test)",
      "Bash(npm run build)",
      "Bash(git status)",
      "Bash(git diff)",
      "Bash(git log *)",
      "Bash(mvn test*)",
      "Bash(mvn compile)",
      "Read(*)",
      "Glob(*)",
      "Grep(*)"
    ],
    "deny": [
      "Bash(rm -rf *)",
      "Bash(git push --force *)",
      "Bash(git reset --hard *)",
      "Bash(curl *)",
      "Write(**/*.env)",
      "Write(**/*credentials*)"
    ]
  }
}
```

### 10.4 权限匹配规则

权限使用 **glob 风格匹配**：

| 模式 | 匹配范围 | 示例 |
|---|---|---|
| `Bash(npm test)` | 精确匹配 `npm test` | 只允许这确切一条命令 |
| `Bash(npm *)` | 以 `npm ` 开头的命令 | `npm test`, `npm install`, `npm run build` 等 |
| `Bash(mvn test*)` | 以 `mvn test` 开头的命令 | `mvn test`, `mvn test -Dtest=ClassName` |
| `Bash(*)` | 所有 Bash 命令 | ⚠️ 谨慎使用，极度危险 |
| `Read(src/**)` | `src/` 下所有文件 | 包括子目录中所有文件 |
| `Write(*.java)` | 所有 `.java` 文件 | 项目任意位置的 Java 文件 |
| `Edit(src/main/**)` | `src/main/` 下所有文件编辑 | 限制编辑范围 |

### 10.5 分层配置策略

#### 全局配置（`~/.claude/settings.json`）

放置所有项目都通用的安全权限：

```json
{
  "permissions": {
    "allow": [
      "Read(*)",
      "Glob(*)",
      "Grep(*)"
    ],
    "deny": [
      "Bash(rm -rf *)",
      "Bash(git push --force *)",
      "Bash(sudo *)"
    ]
  }
}
```

#### 项目配置（`.claude/settings.json`）

放置项目特定的权限，签入 git 供团队共享：

```json
{
  "permissions": {
    "allow": [
      "Bash(mvn test*)",
      "Bash(mvn compile)",
      "Bash(mvn clean package)",
      "Bash(git status)",
      "Bash(git diff)",
      "Bash(git log *)"
    ],
    "deny": [
      "Write(**/application*.yml)",
      "Write(**/application*.properties)",
      "Edit(**/pom.xml)"
    ]
  }
}
```

#### 本地配置（`.claude/settings.local.json`）

放置个人偏好的权限，**不签入 git**：

```json
{
  "permissions": {
    "allow": [
      "Bash(npm run dev)",
      "Bash(git commit *)",
      "Write(src/**)"
    ]
  }
}
```

### 10.6 运行时权限控制

#### 临时批准

在 REPL 中，当 Claude 请求执行一个需要确认的操作时：

1. 确认框弹出
2. 按 **Enter** — 批准本次执行
3. 按 **Esc** — 拒绝本次执行
4. 选择 **"Always allow for this project"** — 持久批准该类操作（写入 `settings.local.json`）
5. 输入自定义命令（以 `/` 开头）

#### /auto-approve 模式

```bash
# 开启自动批准（跳过所有确认框）
/auto-approve-on

# 关闭自动批准（恢复确认）
/auto-approve-off
```

> **注意**：`/auto-approve-on` 仅在当前会话有效，退出后失效。用于你完全信任当前任务且不想频繁点确认的场景。

### 10.7 安全最佳实践

1. **默认 deny 危险操作**：`rm -rf`、`git push --force`、`sudo` 等放入 deny 列表
2. **最小权限原则**：只 allow 必要的命令，不要使用 `Bash(*)`
3. **保护配置文件**：deny `application.yml`、`.env` 等敏感配置文件的写入
4. **项目配置签入**：共享的安全规则放入 `.claude/settings.json`
5. **个人配置不签入**：`.claude/settings.local.json` 加入 `.gitignore`
6. **审查 deny 列表**：定期检查 deny 列表是否过度限制导致正常任务受阻

### 10.8 权限配置示例场景

#### 场景一：Java Maven 项目

```json
{
  "permissions": {
    "allow": [
      "Read(*)",
      "Glob(*)",
      "Grep(*)",
      "Bash(mvn *)",
      "Bash(git status)",
      "Bash(git diff)",
      "Bash(git log *)",
      "Bash(java --version)",
      "Edit(src/**)"
    ],
    "deny": [
      "Bash(mvn deploy*)",
      "Bash(git push*)",
      "Write(**/application*.yml)",
      "Write(**/*.env)"
    ]
  }
}
```

#### 场景二：Node.js 前端项目

```json
{
  "permissions": {
    "allow": [
      "Read(*)",
      "Glob(*)",
      "Grep(*)",
      "Bash(npm *)",
      "Bash(npx *)",
      "Bash(git status)",
      "Bash(git diff)",
      "Bash(node --version)",
      "Edit(src/**)"
    ],
    "deny": [
      "Bash(npm publish*)",
      "Bash(git push*)",
      "Write(**/.env*)",
      "Write(**/*secret*)"
    ]
  }
}
```

#### 场景三：个人实验项目（宽松配置）

```json
{
  "permissions": {
    "allow": [
      "Bash(*)",
      "Read(*)",
      "Write(*)",
      "Edit(*)",
      "Glob(*)",
      "Grep(*)",
      "WebSearch(*)",
      "WebFetch(*)"
    ],
    "deny": [
      "Bash(rm -rf /*)",
      "Bash(sudo *)",
      "Bash(> /dev/*)"
    ]
  }
}
```

> ⚠️ 场景三仅适用于完全隔离的个人实验环境。生产项目请使用场景一/二的严格配置。

---

## 附录 A：常见问题

### Q: Claude Code 支持哪些语言？
A: Claude Code 支持所有主流编程语言。对 Java、Python、TypeScript/JavaScript、Go、Rust、C/C++ 等语言的代码理解和生成效果最佳。

### Q: 如何切换模型？
A: 使用 `/config` 命令交互式切换，或直接修改 `settings.json` 中的 `model` 字段。

### Q: 对话太长会不会越来越慢？
A: 会。上下文增长会导致每次 API 调用处理更多 token，成本上升、速度下降。建议定期使用 `/compact` 压缩。

### Q: 可以断点续传吗（会话恢复）？
A: Claude Code 支持会话持久化（session/ 目录）。退出后重新进入同一项目，对话历史保留，可以继续之前的工作。

### Q: 如何在一个命令中完成多个操作？
A: Claude 支持复杂的多步骤任务，只需在一个提示中描述完整需求。Claude 会自动拆解步骤并逐步执行。

### Q: memory 和 CLAUDE.md 的区别？
A: CLAUDE.md 是项目级的静态指令文件，签入 git 供团队共享。Memory 是用户个人的动态记忆，存储在本地 `.claude/memory/`。

---

## 附录 B：术语速查

| 术语 | 全称 | 说明 |
|---|---|---|
| CC | Claude Code | Anthropic 的 CLI AI 编程助手 |
| CLAUDE.md | — | 项目级 AI 指令文件 |
| ReAct | Reasoning + Acting | 思考-行动-观察循环模式 |
| Agent | — | 能自主执行多步任务的 AI 代理 |
| MCP | Model Context Protocol | 模型上下文协议，连接外部工具服务 |
| Hook | — | 生命周期钩子，在特定时机执行自定义脚本 |
| Skill | — | 工作流模块，封装特定领域的知识和工具 |
| Compaction | — | 上下文压缩，将早期对话总结以释放 token |
| Token | — | LLM 输入/输出的基本计量单位 |