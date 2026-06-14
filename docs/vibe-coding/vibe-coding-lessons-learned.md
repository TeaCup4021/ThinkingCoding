# Vibe Coding 实践反思：问题成因、预防与沟通策略

## 背景

在 ThinkingCoding 项目中，通过 vibe coding（AI 辅助编程）实现了基于 GitNexus 知识图谱 + DashScope Embedding + PostgreSQL pgvector 的 RAG 图嵌入索引系统。开发过程中遇到了 6 个典型问题，本文分析这些问题的 vibe coding 成因、预防策略，以及开发者应如何更有效地向 AI 描述需求。

---

## 问题清单

| # | 问题 | 表象 | 类型 |
|---|------|------|------|
| 1 | `PgVectorConfig` getter 找不到 | 编译错误 | 框架行为偏差 |
| 2 | 异步索引时 MCP 未连接 | 运行时 `MCP服务器未连接` | 初始化顺序错误 |
| 3 | DashScope batch size 超限 | API 报错 `should not be larger than 10` | 外部约束未知 |
| 4 | YAML `baseURL` 未映射到 `baseUrl` | 配置值为 null | 命名约定不匹配 |
| 5 | Embedding 维度硬编码错误 | 表结构与向量维度不一致 | 多分支逻辑遗漏 |
| 6 | 测试 NPE（null → LinkedHashMap） | 测试崩溃 | 边界值未覆盖 |

---

## 逐一分析

### 问题 1：Lombok `@Data` 在内部静态类上未生成 getter

**发生了什么**

`PgVectorConfig` 是 `AppConfig` 的 `public static class`，加了 `@Data` 注解。编译时调用 `getUser()`、`getPassword()` 等方法报错不存在，最终改用手写 getter/setter。

**Vibe Coding 成因**

AI 生成代码时，默认认为 "加 `@Data` 就能生成 getter/setter" 是一个通用规则。但 Lombok 的行为受以下因素影响：
- **编译器版本**（javac vs Eclipse compiler）
- **Lombok 版本与编译器兼容性**
- **内部静态类的注解处理时机**（某些组合下 Lombok 不处理嵌套类）
- **项目是否配置了 `annotationProcessor` 路径**

AI 不了解当前项目的具体 Lombok/编译器版本组合，无法判断这个规则在此环境中是否成立。

**如何避免**

1. **开发者侧**：在项目 CLAUDE.md 或引导语中声明框架的已知坑位：
   ```
   Lombok: 内部静态类不要依赖 @Data，用显式 getter/setter。
   ```
2. **AI 侧策略**：当生成内部类的 `@Data` 时，如果没有明确的编译验证环境，优先选择手写或同时提供两种方案。
3. **验证时机**：写完配置类后**立即编译**，不要等所有代码写完再一起编译。

**开发者应该如何描述**

> ❌ "给 AppConfig 加一个 pgvector 配置类"
>
> ✅ "给 AppConfig 加一个 pgvector 配置类，作为内部静态类。注意：本项目的 Lombok 在某些情况下对内部静态类不会生成 getter，如果加 @Data 的话顺带确认一下编译是否通过，不行就手写。"

---

### 问题 2：异步索引线程在 MCP 服务器连接之前启动

**发生了什么**

`GraphEmbeddingIndexer` 的后台索引线程在 `initializeMCPTools()` 之前就被触发了。索引线程调用 `mcpService.callTool("gitnexus", ...)` 时，gitnexus 服务器还没连接，导致运行时失败。

原代码结构：
```
初始化 Embedding 组件 → 触发异步索引（❌ 此时 MCP 未就绪）
→ 初始化 MCP 工具 → 注册其他服务
```

**Vibe Coding 成因**

这是一个典型的 **依赖时序盲区**。AI 在编写代码时：

- 写"初始化 Embedding 并触发异步索引"时，关注点是 "Embedding 组件本身是否就绪"
- 写"调用 GitNexus 获取符号"时，关注点是 "MCP 服务能否调用工具"

但**没有跨越这两个片段做端到端的时序校验**。AI 看到的是一段一段的代码，而运行时是一条连续的时间线。AI 无法自动感知到：`new Thread(...).start()` 虽然写在这个位置，但它的实际执行点可能发生在 `initializeMCPTools()` 之前。

更深层的原因：**依赖关系没有被显式告知 AI**。如果开发者在描述任务时没有说"索引依赖 gitnexus MCP 服务器，必须先连接"，AI 只能根据代码书写顺序猜测。

**如何避免**

1. **开发者侧**：描述任务时主动说明组件间的依赖链：
   ```
   索引 → GitNexus → MCP 连接 → MCP 配置
   每一步依赖上一步完成。
   ```
2. **AI 侧策略**：遇到异步线程（`new Thread`、`CompletableFuture`、`@Async`）时，主动问："这个线程依赖哪些服务？它们在此刻是否已经初始化完成？"
3. **代码审查清单**：对于所有 `new Thread` 或异步启动点，回溯其内部调用的每一个服务，确认初始化顺序。

**开发者应该如何描述**

> ❌ "在初始化时如果 pgvector 表为空就后台异步建索引"
>
> ✅ "在初始化时，**等 MCP 服务器全部连接完成后**，检查 pgvector 表是否为空，如果为空则启动后台线程异步建索引。索引过程通过 MCP 调用 GitNexus，所以 MCP 必须先就绪。注意初始化顺序：Embedding 组件 → MCP 连接 → 异步索引触发。"

---

### 问题 3：DashScope text-embedding-v4 批量限制为 10

**发生了什么**

`BATCH_SIZE` 最初设为 15，调用 DashScope embedding API 时报错：`batch size is invalid, it should not be larger than 10`。

**Vibe Coding 成因**

AI 选择 batch size 15 是基于"通用经验"（很多 embedding API 的批量上限是 16-32），但没有查询 **DashScope text-embedding-v4 的具体文档**。原因：

- AI 的知识截止日期可能早于该模型的发布，不知道这个具体限制
- 开发者没有提供 API 文档链接或说明限制
- 代码生成时 AI 没有主动搜索验证这个约束

深层原因：**外部 API 的约束是"隐性知识"**，不在代码库中，不在对话历史中，只在 API 文档里。

**如何避免**

1. **开发者侧**：在使用特定 API 前，主动告知约束，或让 AI 先搜索文档：
   ```
   使用 DashScope text-embedding-v4，先搜索一下它的 batch size 和维度限制。
   ```
2. **AI 侧策略**：当代码中硬编码了 batch size、超时时间、速率限制等"魔数"时，加上注释说明来源，或用常量命名使其可配置：
   ```java
   private static final int BATCH_SIZE = 10; // DashScope text-embedding-v4 上限
   ```
3. **防御性编程**：batch size 应设计为可配置项（`config.yaml` 中可覆盖），而不是硬编码常量。

**开发者应该如何描述**

> ❌ "批量调用 embedding API"
>
> ✅ "批量调用 DashScope text-embedding-v4 API。注意：这个 API 的批量上限是 10 条文本，维度是 1024。batch size 做成可配置的，默认值 10，在 config.yaml 中可以覆盖。"

---

### 问题 4：YAML `baseURL` 与 Java `@JsonProperty("baseUrl")` 大小写不匹配

**发生了什么**

`config.yaml` 中写的是 `baseURL:`，Java 字段注解是 `@JsonProperty("baseUrl")`。Jackson 默认精确匹配大小写，导致值映射不上，`baseUrl` 为 null。最终加了 `@JsonAlias` 才解决。

**Vibe Coding 成因**

AI 写 Java 类时按 Java 命名惯例（camelCase: `baseUrl`），读 YAML 时按 YAML/JSON 常见惯例（camelCase）。但没有考虑到**用户实际编写的 YAML 可能使用不同的命名风格**（`baseURL` 这种混合大小写在配置文件中很常见）。

深层原因：**AI 在"写 Java 类"和"写 YAML 配置"时是分离的两个上下文**，没有做 cross-paradigm 的一致性检查。

**如何避免**

1. **开发者侧**：提供 YAML 配置片段或至少提供键名格式：
   ```
   YAML 里我用的是 baseURL，Java 字段帮我兼容一下。
   ```
2. **AI 侧策略**：生成配置映射类时，主动使用 `@JsonAlias` 覆盖常见变体（全小写、camelCase、PascalCase、snake_case）。这是一个低成本、高收益的防御性做法。
3. **验证时机**：写完配置类后，加一个单元测试，用实际 YAML 字符串反序列化验证所有字段都能正确映射。

**开发者应该如何描述**

> ❌ "rag 配置加 baseUrl 字段"
>
> ✅ "rag 配置加 embedding API 的 base URL 字段。我的 YAML 里写的是 `baseURL`（大写 URL），Java 字段按 camelCase 命名，但要确保 Jackson 能正确映射，建议加 @JsonAlias 兼容 baseURL、base_url 等常见写法。"

---

### 问题 5：Embedding 维度默认值逻辑不覆盖所有模型

**发生了什么**

`resolveDefaultDimensions()` 方法最初只处理了 `text-embedding-3-large`（3072）和 `text-embedding-3-small`（1536），默认 fallback 是 3072。项目中实际使用的是 `text-embedding-v4`，其维度是 1024。导致 pgvector 列定义为 3072，但实际向量是 1024 维，插入时类型不匹配。

**Vibe Coding 成因**

这是一个经典的 **"写了 if-else，但没有覆盖全部分支"** 问题。AI 在处理维度映射时：

- 已知常见模型的维度（来自训练数据）
- 但没有将 "默认值" 设为最安全的选项
- 没有提示用户："我只知道这些模型的维度，如果使用其他模型请告知"

深层原因：**AI 的"已知"是不完整的快照**，但 AI 倾向于用已知信息做出自信的推断（"不知道的模型就当 3072 处理"），而不是明确表达不确定性。

**如何避免**

1. **开发者侧**：明确告知模型名和参数：
   ```
   使用 text-embedding-v4，维度 1024。
   ```
2. **AI 侧策略**：对于需要"枚举映射"的逻辑，永远保留一个"未知则报错或查询"的路径，不要用"猜测的默认值"：
   ```java
   default -> throw new IllegalArgumentException("Unknown model: " + modelName);
   ```
   或者在无法确定时，把维度做成**必填配置项**而非自动推断。
3. **核心原则**：**显式优于隐式**。依赖自动推断的逻辑比显式配置更容易出错。

**开发者应该如何描述**

> ❌ "根据模型名自动推断维度"
>
> ✅ "加一个维度默认值解析方法。已知维度：text-embedding-3-large=3072, text-embedding-3-small=1536, text-embedding-v4=1024, bge-m3=1024。其他模型返回 1024 作为安全默认值。另外在 RagConfig 中加一个可选的 dimensions 字段，允许用户在 YAML 中显式覆盖。"

---

### 问题 6：测试中传入 null 导致 NPE

**发生了什么**

`GraphEnrichedDocumentTest` 中构造 `CodeGraphSymbol` 时，`referenceKinds` 参数传了 `null`，构造函数内部 `new LinkedHashMap<>(null)` 抛出 NPE。

**Vibe Coding 成因**

AI 写测试时关注的是 "验证空引用集合的文档输出"，但用 `null` 表示"空"。然而 `CodeGraphSymbol` 的构造函数（可能是之前由另一个 AI 调用生成的）没有对输入做 null-safety 处理，直接 `new LinkedHashMap<>(parameter)`。

深层原因：**生产代码和测试代码是在不同时刻生成的，各自的防御性假设不一致**。写生产代码的 AI 假设"调用者不会传 null"，写测试代码的 AI 假设"传 null 应该等同于空集合"。

**如何避免**

1. **开发者侧**：要求 AI 对构造函数的引用类型参数做 null 防护：
   ```
   CodeGraphSymbol 构造函数中，集合类型参数用 Collections.emptyXxx() 或 new Xxx() 兜底。
   ```
2. **AI 侧策略**：生成构造函数时，对集合类型参数统一做 null-safe 处理（一行三目运算符即可）；生成测试时，用 `Collections.emptyMap()` / `Map.of()` 而非 `null` 表示空集合。
3. **测试原则**：测试中用的数据应该和生产代码有相同的"语义契约"。如果生产代码不接受 null，就不要用 null 测试。

**开发者应该如何描述**

> ❌ "写一个空引用集合的测试"
>
> ✅ "写一个空引用集合的测试。CodeGraphSymbol 的 referenceKinds 参数是 Map 类型，空集合用 Map.of() 表示，不要传 null。另外，顺便检查一下 CodeGraphSymbol 构造函数，如果对集合参数没有做 null 防护，加上。"

---

## 根因归纳

从 vibe coding 的角度，这 6 个问题可以归入三类根因：

### A. 运行时上下文缺失（问题 2、3、4）

AI 编写代码时，没有"运行时环境"的感知。
- 它不知道 MCP 服务器何时连接（问题 2）
- 它不知道 DashScope API 的 batch 限制（问题 3）
- 它不知道用户 YAML 中的命名风格（问题 4）

**核心矛盾**：AI 的代码生成依赖"通用知识 + 代码上下文"，但 bug 往往发生在"特定环境 + 运行时行为"的交界处。

### B. 片段式生成缺少跨组件校验（问题 1、6）

AI 一次通常只生成/修改 1-2 个文件。
- 写 `AppConfig` 时没校验 Lombok + 内部类的编译行为（问题 1）
- 写测试时没校验构造函数对 null 的防御程度（问题 6）

**核心矛盾**：代码的**正确性约束是跨文件的**，但 vibe coding 的**生成是单文件的**。

### C. 不确定时选择了自信的猜测而非显式确认（问题 5、3）

- 不知道模型维度 → 猜测一个默认值（问题 5）
- 不知道 API 限制 → 猜测一个通用值（问题 3）

**核心矛盾**：AI 被训练为"提供答案"而非"表达不确定性"。当缺少关键信息时，AI 倾向于推断而非询问。

---

## 开发者最佳实践

### 1. 描述功能时，附带边界约束

```
✅ 好的描述：
"实现批量 embedding 调用。约束：
- API: DashScope text-embedding-v4
- 批量上限: 10 条/次
- 维度: 1024
- 超时: 30s
- 认证: Bearer token，通过 config.yaml apiKey 传入"
```

### 2. 描述架构时，明确组件依赖链

```
✅ 好的描述：
"初始化顺序：
1. AppConfig / MCPConfig 加载
2. Embedding 组件创建（不触发索引）
3. MCP 服务器连接
4. [此时才触发] 检查 pgvector → 异步索引
顺序不能乱，每一步依赖前一步的结果。"
```

### 3. 给出 YAML/配置的确切格式

```
✅ 好的描述：
"RAG 配置段 YAML 格式如下，请按此映射 Java 字段：
rag:
  baseURL: "https://..."
  apiKey: "sk-..."
  embeddingModel: "text-embedding-v4"
  pgvector:
    host: "localhost"
    port: 5432
    ..."
```

### 4. 要求 AI 做防御性编程

```
✅ 追加指令：
"所有构造函数对集合参数做 null-safe 处理。"
"配置映射类统一加 @JsonAlias 覆盖常见大小写变体。"
"硬编码的数值常量加注释说明来源，或做成可配置项。"
```

### 5. 分步验证，不要攒到最后

```
✅ 正确的节奏：
写完配置类 → 编译 → 通过
写完 EmbeddingService → 写个 main 方法调一下 API → 返回正常
写完 GraphEmbeddingStore → 跑单元测试 → 通过
写完索引流程 → 启动应用 → 观察日志
```

每完成一个独立模块就验证，不要把 6 个文件全写完再一起跑 —— 那样会同时暴露 6 个问题，排错像解谜。

### 6. 维护 CLAUDE.md 中的"已知坑位"

在项目的 CLAUDE.md 中持续积累从 vibe coding 中发现的坑：

```markdown
## Gotchas (from vibe coding experience)

- Lombok @Data on inner static classes may not generate getters — write explicit ones
- DashScope text-embedding-v4: max batch = 10, dims = 1024
- MCP servers must be connected before GitNexus-dependent indexing starts
- Use @JsonAlias on config fields to tolerate YAML naming variants
- CodeGraphSymbol: constructors expect non-null Map/List; use Map.of() / List.of() in tests
```

这样随着项目推进，AI 能持续从历史教训中受益，而不是重复踩坑。

---

## 总结

Vibe coding 的高效不在于"一次性写对所有代码"，而在于**快速生成 + 快速发现问题 + 快速修复**的短反馈循环。本文记录的 6 个问题都在几分钟到十几分钟内被发现和修复，这正是 vibe coding 的正常节奏。

要减少这类问题，关键是**开发者在描述需求时，将隐性的环境约束和依赖关系显性化** —— AI 无法读取你的运行时环境、API 文档和心中的架构蓝图，但如果这些信息被清晰地表述出来，AI 生成的代码质量会显著提升。
