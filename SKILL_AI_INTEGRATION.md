# Skill AI 集成改造说明

## 📋 改造概述

本次改造使 ThinkingCoding 项目具备 **AI 感知 skill 并在需要时主动调用** 的功能。

## ✅ 已完成的改造

### 1. Skill 接口增强
- **文件**: `src/main/java/com/thinkingcoding/skill/Skill.java`
- **改动**: 
  - 添加 `getDescription()` 方法，返回 skill 的描述信息
  - 添加 `getInputSchema()` 方法，返回 skill 的参数 schema（JSON Schema 格式）

### 2. AutoTestSkill 元数据实现
- **文件**: `src/main/java/com/thinkingcoding/skill/autotest/AutoTestSkill.java`
- **改动**:
  - 实现 `getDescription()`: "自动生成单元测试代码，并在测试失败时自动修复..."
  - 实现 `getInputSchema()`: 定义 source（必需）和 test（可选）参数

### 3. SkillToolAdapter 适配器
- **文件**: `src/main/java/com/thinkingcoding/skill/SkillToolAdapter.java` (新建)
- **功能**:
  - 将 Skill 包装成 BaseTool，使其能被 AI 工具调用系统识别
  - 解析 AI 传入的 JSON 参数并执行 skill
  - 返回格式化的执行结果

### 4. Skill 注册机制
- **文件**: `src/main/java/com/thinkingcoding/core/ThinkingCodingContext.java`
- **改动**:
  - 添加 `initializeSkills()` 方法，在启动时遍历配置的 skills
  - 为每个 enabled 的 skill 创建实例并注册到 ToolRegistry
  - 特殊处理 AutoTestSkill 的依赖注入（需要 AIService, ToolRegistry, AppConfig）

### 5. 系统提示词增强
- **文件**: `src/main/java/com/thinkingcoding/service/ContextManager.java`
- **改动**:
  - 添加 `availableSkills` 字段存储可用 skill 列表
  - 修改 `buildProjectContextMessage()` 支持传入 skill 列表
  - 在系统提示词中添加 "🎯 可用 Skill 工具" 章节，列出所有可用 skill 及其描述和调用示例

### 6. ContextManager 初始化
- **文件**: `src/main/java/com/thinkingcoding/core/ThinkingCodingContext.java`
- **改动**:
  - 在创建 ContextManager 后，设置可用的 skill 列表
  - 确保 AI 在每次对话时都能获取 skill 信息

## 🔄 工作流程

### 启动阶段
1. 加载 config.yaml 中的 skills 配置
2. 创建 ContextManager 并设置 availableSkills
3. 创建 AIService（LangChainService）
4. 调用 `initializeSkills()`:
   - 遍历配置的 skills
   - 为每个 skill 创建实例（AutoTestSkill 特殊处理）
   - 用 SkillToolAdapter 包装 skill
   - 注册到 ToolRegistry

### AI 对话阶段
1. LangChainService 构建工具规格时，从 ToolRegistry 获取所有工具（包括 skill）
2. ContextManager 构建系统提示词时，包含可用 skill 列表和说明
3. AI 收到请求时，可以看到：
   - 系统提示词中的 skill 说明
   - 工具规格中的 skill 参数 schema
4. AI 决定调用 skill 时，发送工具调用请求
5. SkillToolAdapter 接收请求，解析参数，执行 skill
6. 返回执行结果给 AI

## 📝 配置示例

config.yaml 中的 skill 配置：
```yaml
skills:
  - name: "autotest"
    description: "Generate and auto-fix tests"
    className: "com.thinkingcoding.skill.autotest.AutoTestSkill"
    enabled: true
    config:
      maxRetries: 3
```

## 🧪 测试方法

### 方法 1: 命令行交互模式
```bash
mvn exec:java -Dexec.mainClass="com.thinkingcoding.ThinkingCodingCLI"
```

然后与 AI 对话：
```
用户: 请为 HelloWorld.java 创建单元测试
AI: [应该调用 autotest skill]
```

### 方法 2: 直接调用 skill 命令（原有方式仍然可用）
```bash
thinking skill --name autotest --source HelloWorld.java
```

## 🎯 关键改进点

1. **AI 感知**: AI 现在知道有哪些 skill 可用，以及何时使用它们
2. **自动调用**: AI 可以根据用户需求主动调用 skill，无需用户手动指定
3. **参数验证**: 通过 JSON Schema 确保 AI 传入正确的参数
4. **向后兼容**: 原有的命令行 skill 调用方式仍然有效

## ⚠️ 注意事项

1. **依赖顺序**: Skill 必须在 AIService 创建后注册，因为某些 skill（如 AutoTestSkill）需要 AIService 实例
2. **构造函数**: 目前只支持无参构造函数的 skill，或通过特殊处理的 skill（如 AutoTestSkill）
3. **性能**: Skill 作为工具注册后，会增加每次 AI 调用的 token 消耗（工具规格 + 系统提示词）

## 🔮 未来扩展

1. 支持更多类型的 skill（只需实现 Skill 接口并配置即可）
2. 添加 skill 执行进度反馈
3. 支持 skill 之间的组合调用
4. 添加 skill 执行历史记录

## 📊 编译状态

✅ 编译成功（2026-05-02）
- 91 个源文件编译通过
- 无错误，仅有少量警告（与本次改造无关）
