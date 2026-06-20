package com.thinkingcoding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ChatMessage;
import com.thinkingcoding.service.memory.DeepSeekTokenCounter;
import com.thinkingcoding.service.memory.WorkingMemory;
import com.thinkingcoding.service.memory.WorkingMemoryConfig;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 上下文管理器（facade）。
 *
 * <p>职责收敛为：组装固定段（项目上下文 / 当前上下文）、统一 token 计数、
 * 把"可变历史"的窗口构建委托给 {@link WorkingMemory}。
 *
 * <p>历史的三策略（滑动窗口 / Token / 混合）与全量摘要分支已移除，
 * 统一为单一策略：token 预算 + 轮次完整性 + 滚动摘要（见 WorkingMemory）。
 */
public class ContextManager {
    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final AppConfig appConfig;
    private List<AppConfig.SkillConfig> availableSkills; // 🔥 存储可用的 skill 列表

    // Current Context 动态数据
    private volatile String lastPlanSummary;
    private volatile String lastToolResults;

    // 默认配置
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 3000;  // 为历史预留3000 tokens
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;  // 模型单次最大输出 tokens

    private int maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;
    private int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;

    // token usage 仅用于日志/核对，不参与发送前的窗口决策
    private volatile int lastPromptTokens = -1;
    private volatile int lastCompletionTokens = -1;
    private volatile int lastTotalTokens = -1;

    private static final Path TRANSCRIPT_DIR = Paths.get("transcripts");
    private final ObjectMapper objectMapper;

    private OpenAiChatModel ChatModel;
    private Function<String, String> conversationSummarizer;

    private final DeepSeekTokenCounter tokenCounter;
    private final WorkingMemoryConfig workingMemoryConfig;
    private WorkingMemory workingMemory;

    public ContextManager(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        initializeChatModel();
        this.conversationSummarizer = this::callLlmForSummary;
        this.tokenCounter = DeepSeekTokenCounter.getInstance();
        this.workingMemoryConfig = new WorkingMemoryConfig();
        this.workingMemory = new WorkingMemory(workingMemoryConfig, tokenCounter,
                this::summarizeConversation);
        loadConfiguration();
    }

    /**
     * 从配置加载参数
     */
    private void loadConfiguration() {
        AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(appConfig.getDefaultModel());
        if (modelConfig == null) {
            return;
        }

        Integer configuredMaxOutput = modelConfig.getMaxTokens();
        if (configuredMaxOutput != null && configuredMaxOutput > 0) {
            this.maxOutputTokens = configuredMaxOutput;
        }

        Integer configuredMaxContext = modelConfig.getMaxContextTokens();
        if (configuredMaxContext != null && configuredMaxContext > 0) {
            this.maxContextTokens = configuredMaxContext;
        } else if (this.maxOutputTokens > 0 && this.maxContextTokens <= this.maxOutputTokens) {
            this.maxContextTokens = this.maxOutputTokens + DEFAULT_MAX_CONTEXT_TOKENS;
        }
    }

    private void initializeChatModel() {
        try {
            AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(appConfig.getDefaultModel());
            if (modelConfig != null) {
                this.ChatModel = createDeepSeekModel(modelConfig);
            }
        } catch (Exception e) {
            System.err.println("初始化模型失败: " + e.getMessage());
        }
    }

    private OpenAiChatModel createDeepSeekModel(AppConfig.ModelConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseURL())
                .apiKey(config.getApiKey())
                .modelName(config.getName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 获取 AI 上下文，根据当前策略对 fullHistory 进行管理和截断
     * 核心方法：在每次调用 AI 之前，先调用这个方法获取经过管理的上下文历史
     * 输入 fullHistory 是完整的对话历史，输出是经过策略处理后的消息列表，供 AI 使用
     * 不同策略的实现细节在 applySlidingWindow、applyTokenLimit 和 applyHybridStrategy 方法中
     * 这个方法的设计目标是：在保证 AI 上下文信息足够的前提下，最大程度地压缩历史消息，防止 Token 超限导致的调用失败
     * 但当前方法不会处理系统提示词
     * @param fullHistory
     * @return
     */
    /**
     * 获取 AI 上下文：在工作记忆预算内构建发送给模型的历史。
     *
     * <p>委托 {@link WorkingMemory#buildWindow}。此重载用默认预算
     * （maxContextTokens - maxOutputTokens），不扣固定段；需要扣固定段时
     * 调用 {@link #getContextForAI(List, int)}。
     */
    public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
        return getContextForAI(fullHistory, getCompressionThreshold());
    }

    /**
     * 获取 AI 上下文，预算由调用方给定。
     *
     * @param fullHistory  完整对话历史（不含固定段）
     * @param budgetTokens 工作记忆可用预算 = maxContextTokens - maxOutputTokens - 固定段tokens
     */
    public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory, int budgetTokens) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> result = workingMemory.buildWindow(fullHistory, budgetTokens);
        logContextStatistics(fullHistory, result);
        return result;
    }

    /** 统一 token 计数入口（精确分词，回退字符启发式）。 */
    public int estimateTokens(String text) {
        return tokenCounter.count(text);
    }

    /** 暴露工作记忆参数，供编排层/配置调整。 */
    public WorkingMemoryConfig getWorkingMemoryConfig() {
        return workingMemoryConfig;
    }

    /**
     *  新增：构建固定的项目上下文消息
     * 这个上下文会在每次 AI 调用时注入，永远不会被截断
     *
     * @return 项目上下文系统消息，如果无法获取则返回 null
     */
    public ChatMessage buildProjectContextMessage() {
        return buildProjectContextMessage(availableSkills);
    }

    /**
     *  新增：构建固定的项目上下文消息（支持传入 skill 列表）
     * 这个上下文会在每次 AI 调用时注入，永远不会被截断
     *
     * @param availableSkills 可用的 skill 列表，用于告诉 AI 有哪些 skill 可以调用
     * @return 项目上下文系统消息，如果无法获取则返回 null
     */
    public ChatMessage buildProjectContextMessage(List<AppConfig.SkillConfig> availableSkills) {
        try {
            String cwd = System.getProperty("user.dir");
            if (cwd == null || cwd.isEmpty()) {
                return null;
            }

            StringBuilder context = new StringBuilder();
            context.append("##  重要指令\n\n");
            context.append("! **你必须始终使用中文回答用户的所有问题！**\n");
            context.append("! **所有的解释、说明、代码注释都必须使用中文！**\n\n");

            context.append("##  你的角色定位\n\n");
            context.append("你是一位资深的编程助手，由 ThinkingCoding 框架驱动。你的核心特点：\n\n");
            context.append("1. **主动思考和分析** - 不要直接执行，先检查现状\n");
            context.append("2. **提供多个选项** - 当遇到已存在的文件或复杂情况时，列出3-4个选项让用户选择\n");
            context.append("3. **智能决策** - 检查文件是否存在、分析当前项目状态\n");
            context.append("4. **友好交互** - 清晰解释每一步，让用户感觉有一位专家在帮忙\n\n");

            context.append("##  当前工作环境\n\n");
            context.append("工作目录: ").append(cwd).append("\n\n");

            context.append("**路径支持：**\n");
            context.append("- **相对路径**：`sessions/test.json` - 相对于当前工作目录\n");
            context.append("- **绝对路径**：`/Users/zengxinyue/Desktop/test.txt` - 可以访问任何目录\n");
            context.append("- **用户主目录**：`~/Desktop/test.txt` - 使用 ~ 代表用户主目录\n");
            context.append("- **上级目录**：`../other_project/file.txt` - 可以访问父目录\n\n");

            context.append("##  智能工作流程（重要！）\n\n");

            context.append("### 🧭 复杂任务的结构化规划（TODO指南针）\n\n");
            context.append("- 当用户分配复杂的、多步骤的编程或重构任务时，不要立刻编写全部代码。\n");
            context.append("- 第一步必须是调用 manage_todo(action=\"add\", ...) 创建任务列表。\n");
            context.append("- 在接下来的每一次行动中，系统都会通过 <system-reminder> 提示你当前进度。\n");
            context.append("- 完成一个小目标后，调用 manage_todo(action=\"complete\", id=\"...\") 更新进度，再进行下一步。\n\n");

            context.append("###  当用户要求创建/编写代码文件时：\n\n");
            context.append("**重要：直接生成代码，不要先检查文件是否存在！**\n\n");
            context.append("当用户说\"写一个Java代码\"、\"创建HelloWorld程序\"等时：\n\n");
            context.append("**步骤 1：直接创建文件并生成代码**\n");
            context.append("1. 简短说明你要创建什么\n");
            context.append("2. 使用原生工具调用 `file_manager(command=\"write\", path, content)`\n");
            context.append("3. 不要在文本中输出伪命令\n");
            context.append("4. 调用工具后等待系统返回结果\n\n");

            context.append("### 🗑 当用户要求删除文件/目录时：\n\n");
            context.append("**重要：直接执行删除命令，不需要先检查目录内容！**\n\n");

            context.append("**智能路径识别规则：**\n");
            context.append("- UUID 格式的 JSON 文件（如 41e6f846-b709-4511-8fde-86cfe0e86809.json）→ 在 sessions 目录下\n");
            context.append("- 代码文件（.java/.py/.js 等）→ 通常在当前目录\n");
            context.append("- 明确指定路径的 → 使用指定路径\n\n");

            context.append("**示例1：删除 session 文件**\n");
            context.append("用户说：\"删除 41e6f846-b709-4511-8fde-86cfe0e86809.json\"\n");
            context.append("你应该：调用工具执行 `rm sessions/41e6f846-b709-4511-8fde-86cfe0e86809.json`。\n\n");

            context.append("**示例2：删除目录下所有文件**\n");
            context.append("用户说：\"删除sessions下的所有文件\"\n");
            context.append("你应该：调用工具执行 `rm sessions/*`。\n\n");

            context.append("**错误示例（不要这样做）：**\n");
            context.append(" 不要忽略文件路径：`rm 41e6f846-xxx.json` ← 错误！应该是 `rm sessions/41e6f846-xxx.json`\n");
            context.append(" 不要先调用 List 查看目录\n");
            context.append(" 不要在删除前询问确认（系统会自动处理确认）\n");
            context.append(" 不要在工具调用后编造结果\n\n");

            context.append("###  当用户要求查看/列出文件或目录时：\n\n");
            context.append("直接调用相应工具，不需要额外说明：\n");
            context.append("- 查看文件内容：`file_manager(command=\"read\", path)`\n");
            context.append("- 列出目录：`file_manager(command=\"list\", path)`\n");
            context.append("- 执行命令：`command_executor(command)`\n\n");

            context.append("**正确示例（用户：写一个HelloWorld）：**\n");
            context.append("好的，我来帮你创建一个简单的Java程序。\n\n");
            context.append("（随后通过 `file_manager` 工具调用传入 command=\"write\"、文件路径和完整代码内容）\n\n");

            context.append("**错误示例（不要这样做）：**\n");
            context.append(" 不要先调用 `command_executor(ls -la *.java)` 检查文件\n");
            context.append(" 不要先调用 `file_manager(command=\"read\", path=HelloWorld.java)` 检查文件\n");
            context.append(" 不要问用户\"需要检查现有文件吗？\"\n\n");

            context.append("! **关键：你必须输出完整的代码内容在代码块中！**\n\n");

            context.append("###  当用户要求修改现有文件时：\n\n");
            context.append("修改代码前必须先使用 code_graph 工具进行分析，确认影响范围后再决定是否修改。\n");
            context.append("这时才需要先读取文件：\n");
            context.append("1. 使用 `file_manager(command=\"read\", path)` 读取现有内容\n");
            context.append("2. 等待系统返回文件内容\n");
            context.append("3. 根据用户要求修改代码\n");
            context.append("4. 使用 `file_manager(command=\"write\", path, content)` 写入新内容\n\n");

            context.append("###  工具调用关键规则\n\n");
            context.append("**使用模型的原生 Tool Calling 调用工具，不要在文本里拼接命令字符串。**\n\n");
            context.append("- 工具调用会被立即执行\n");
            context.append("- 调用工具后停止推断结果，等待系统返回真实输出\n");
            context.append("- 绝对禁止编造工具结果\n");
            context.append("- 提供选项时不执行，等待用户选择\n\n");

            context.append("##  回答问题的规范\n\n");

            context.append("### ! 重要：区分\"说明\"和\"执行\"\n\n");
            context.append("**当用户只是询问/咨询时（不要执行工具）：**\n");
            context.append("用户问：\"命令有哪些\"、\"有什么功能\"、\"如何使用\" 等\n");
            context.append("你应该：用纯文本说明，**不要触发工具调用**\n\n");

            context.append("**正确示例（用户：命令有哪些）：**\n");
            context.append("我可以帮你：\n");
            context.append("- 创建文件：告诉我\"创建一个HelloWorld.java文件\"\n");
            context.append("- 读取文件：告诉我\"读取HelloWorld.java的内容\"\n");
            context.append("- 列出目录：告诉我\"查看当前目录有什么\"\n");
            context.append("- 删除文件：告诉我\"删除某个文件\"\n\n");

            context.append("**错误示例（不要这样）：**\n");
            context.append(" 不要写：- 创建文件：file_manager(command=\"write\", path, content)  ← 这会被误认为要执行工具\n");
            context.append(" 不要写：- 读取文件：file_manager(command=\"read\", path)  ← 这会触发工具调用\n\n");

            context.append("**当用户提问时（非创建文件）：**\n");
            context.append("- 用简洁、自然的中文回答\n");
            context.append("- 不要使用 markdown 格式（如 ** - 1. 2. 等）\n");
            context.append("- 直接说明，不要过度格式化\n");
            context.append("- 保持对话自然流畅\n\n");
            context.append("**示例（正确）：**\n");
            context.append("是的，我记得！刚才创建的是一个链表实现，包含 ListNode 和 LinkedList 两个类。\n\n");
            context.append("**示例（错误）：**\n");
            context.append("不要使用：**刚才创建的代码包括：** 1. **ListNode类** 这样的格式！\n\n");

            context.append("## ! 禁止事项\n\n");
            context.append("1. 不要输出形如 `file_manager \"...\" \"...\"` 的命令格式\n");
            context.append("2. 不要在没有检查的情况下直接覆盖文件\n");
            context.append("3. 不要在用户选择前就执行操作\n");
            context.append("4. 不要忘记在操作完成后生成总结\n\n");

            // 🔥 添加可用 Skill 列表
            if (availableSkills != null && !availableSkills.isEmpty()) {
                context.append("## 🎯 可用 Skill 工具（重要！）\n\n");
                context.append("你可以调用以下 skill 来执行 specialized 任务。这些是特殊的工具，可以自动完成复杂的工作流程：\n\n");
                context.append("**当用户要求生成测试/单元测试/测试类时：必须直接调用对应 skill（例如 autotest），不要先读取文件或使用其他工具。**\n\n");

                for (AppConfig.SkillConfig skill : availableSkills) {
                    if (skill != null && skill.isEnabled()) {
                        String briefContext = com.thinkingcoding.skill.SkillContextLoader.resolveBriefContext(skill);
                        String description = briefContext != null ? briefContext : "No description";
                        context.append("### **").append(skill.getName()).append("**\n");
                        context.append("- **描述**: ").append(description).append("\n");
                        context.append("- **调用方式**: 使用工具调用 `").append(skill.getName()).append("(source=\"源文件路径\", test=\"可选的测试文件路径\")`\n");
                        
                        // 添加具体示例和触发条件
                        if ("autotest".equalsIgnoreCase(skill.getName())) {
                            context.append("- **何时使用**: \n");
                            context.append("  * 用户要求“创建测试”、“生成单元测试”、“写测试代码”时\n");
                            context.append("  * 用户说“为 XXX.java 写测试”、“给 XXX 添加测试”时\n");
                            context.append("  * 用户询问“如何测试 XXX”、“XXX 的测试怎么写”时\n");
                            context.append("- **示例**: \n");
                            context.append("  * 用户：“为 HelloWorld.java 创建测试” → 调用 `autotest(source=\"HelloWorld.java\")`\n");
                            context.append("  * 用户：“给 UserService 写单元测试” → 调用 `autotest(source=\"UserService.java\")`\n");
                        }
                        context.append("\n");
                    }
                }
                
                context.append("! **重要提示**:\n");
                context.append("- Skill 是自动化工具，会执行完整的 workflows\n");
                context.append("- 当用户请求与 skill 功能匹配时，**必须优先调用 skill**，而不是手动执行步骤\n");
                context.append("- 调用 skill 后，等待它返回结果再继续\n");
                context.append("- **不要**在应该调用 skill 时去搜索文件或手动编写代码\n\n");
            }

            return new ChatMessage("system", context.toString());
        } catch (Exception e) {
            log.warn("无法构建项目上下文: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 输出上下文统计信息
     */
    private void logContextStatistics(List<ChatMessage> fullHistory, List<ChatMessage> managedHistory) {
        boolean compressed = managedHistory.size() < fullHistory.size();
        int budget = getCompressionThreshold();

        log.info("📊 上下文管理统计:");
        log.info("  预算(threshold): {} (maxContext {} - maxOutput {})", budget, maxContextTokens, maxOutputTokens);
        log.info("  上次API用量: prompt={}, total={}", lastPromptTokens, lastTotalTokens);
        log.info("  历史消息数量: {} 条 → 发送历史数量: {} 条", fullHistory.size(), managedHistory.size());

        if (compressed) {
            double compressionRate = (1 - (double) managedHistory.size() / fullHistory.size()) * 100;
            log.info("  ✅ 已压缩！压缩率: {}%", String.format("%.2f", compressionRate));
        } else {
            log.info("  ⏸️  未压缩");
        }
    }

    /**
     * 将消息列表转换为 JSON 字符串（用于传给 LLM）
     */
    private String truncateConversation(List<ChatMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize messages", e);
        }
    }

    private String callLlmForSummary(String prompt) {
        if (ChatModel == null) {
            throw new IllegalStateException("Chat model not initialized");
        }
        ChatResponse response = ChatModel.chat(UserMessage.from(prompt));
        return response.aiMessage().text();
    }

    private String summarizeConversation(String prompt) {
        if (conversationSummarizer == null) {
            return callLlmForSummary(prompt);
        }
        return conversationSummarizer.apply(prompt);
    }

    public void setConversationSummarizer(Function<String, String> conversationSummarizer) {
        this.conversationSummarizer = conversationSummarizer;
    }

    /**
     * 记录上一次 API 的真实 token 用量。仅用于日志/核对，不参与发送前的窗口决策
     * （窗口决策由 WorkingMemory 用精确分词器在发送前完成）。
     */
    public synchronized void recordTokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        if (promptTokens <= 0 && totalTokens <= 0) {
            return;
        }
        lastPromptTokens = promptTokens;
        lastCompletionTokens = completionTokens;
        lastTotalTokens = totalTokens > 0
                ? totalTokens
                : Math.max(0, promptTokens) + Math.max(0, completionTokens);

        log.info("🔢 Token使用记录: prompt={}, completion={}, total={}",
                promptTokens, completionTokens, lastTotalTokens);
    }

    public synchronized void resetTokenUsage() {
        lastPromptTokens = -1;
        lastCompletionTokens = -1;
        lastTotalTokens = -1;
    }

    /** 工作记忆预算（不扣固定段）= maxContextTokens - maxOutputTokens。 */
    int getCompressionThreshold() {
        return maxContextTokens - maxOutputTokens;
    }

    /**
     * 设置最大上下文 Token 数
     */
    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
        log.info("设置最大上下文 Tokens: {}", maxContextTokens);
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
        log.info("设置最大输出 Tokens: {}", maxOutputTokens);
    }

    /**
     * 获取配置摘要
     */
    public String getConfigSummary() {
        return String.format("WorkingMemory(budget=%d, maxContext=%d, maxOutput=%d)",
                getCompressionThreshold(), maxContextTokens, maxOutputTokens);
    }

    /**
     * 🔥 设置可用的 skill 列表
     */
    public void setAvailableSkills(List<AppConfig.SkillConfig> skills) {
        this.availableSkills = skills;
    }

    /**
     * 🔥 获取可用的 skill 列表
     */
    public List<AppConfig.SkillConfig> getAvailableSkills() {
        return availableSkills;
    }

    /** 设置上一轮 Plan 摘要，作为下一轮的"当前目标" */
    public void setLastPlanSummary(String summary) {
        this.lastPlanSummary = summary;
    }

    /** 设置上一轮工具执行结果，完整保留不压缩 */
    public void setLastToolResults(String results) {
        this.lastToolResults = results;
    }

    /**
     * 🔥 构建 Current Context 文本块。
     * 拼接 "当前目标"、"可用工具"、"上一轮执行结果"，
     * 由调用方追加到最新的 UserMessage 末尾。
     */
    public String buildCurrentContext(java.util.Collection<com.thinkingcoding.tools.BaseTool> tools) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("\n\n---\n## 📋 当前上下文\n\n");

        // 当前目标
        String target = lastPlanSummary;
        if (target == null || target.isBlank()) {
            target = "等待用户输入任务目标";
        }
        ctx.append("### 🎯 当前目标\n").append(target).append("\n\n");

        // 可用工具
        ctx.append("### 🔧 可用工具\n");
        if (tools != null && !tools.isEmpty()) {
            for (com.thinkingcoding.tools.BaseTool tool : tools) {
                ctx.append("- **").append(tool.getName()).append("**: ");
                String desc = tool.getDescription();
                if (desc != null) {
                    // 只取第一句作为简要描述
                    int dot = desc.indexOf('.');
                    ctx.append(dot > 0 ? desc.substring(0, dot + 1) : desc);
                }
                ctx.append("\n");
            }
        } else {
            ctx.append("（无可用工具）\n");
        }
        ctx.append("\n");

        // 上一轮工具执行结果
        ctx.append("### ⚡ 上一轮执行结果\n");
        if (lastToolResults != null && !lastToolResults.isBlank()) {
            ctx.append(lastToolResults).append("\n");
        } else {
            ctx.append("（首次对话，尚未执行任何工具）\n");
        }

        return ctx.toString();
    }
}



