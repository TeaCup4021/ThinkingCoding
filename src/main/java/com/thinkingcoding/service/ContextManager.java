package com.thinkingcoding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ChatMessage;
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

/**
 * 上下文管理器
 * 负责管理对话历史的长度，防止 Token 超限
 *
 * 支持两种策略：
 * 1. 滑动窗口：保留最近 N 轮对话
 * 2. Token 控制：根据 Token 数量动态截断
 */
public class ContextManager {
    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final AppConfig appConfig;
    private List<AppConfig.SkillConfig> availableSkills; // 🔥 存储可用的 skill 列表

    // 默认配置
    private static final int DEFAULT_MAX_HISTORY_TURNS = 10;  // 保留10轮（20条消息）
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 3000;  // 为历史预留3000 tokens
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;  // 模型单次最大输出 tokens

    // 策略枚举
    public enum Strategy {
        SLIDING_WINDOW,  // 滑动窗口
        TOKEN_BASED,     // 基于 Token
        HYBRID           // 混合策略
    }

    private Strategy strategy = Strategy.TOKEN_BASED;  // 默认使用 Token 控制
    private int maxHistoryTurns = DEFAULT_MAX_HISTORY_TURNS;
    private int maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;
    private int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;

    private volatile int lastHistoryTokens = -1;
    private volatile int lastPromptTokens = -1;
    private volatile int lastCompletionTokens = -1;
    private volatile int lastTotalTokens = -1;

    private static final Path TRANSCRIPT_DIR = Paths.get("transcripts");
    private final ObjectMapper objectMapper;

    private OpenAiChatModel ChatModel;

    public ContextManager(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        initializeChatModel();
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
    public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChatMessage> result;

        //自动压缩，只保留轮询中最后一个 user 消息之前的工具调用结果，且只保留前后各200字符，中间省略，标明工具名称，以节省 token
        List<ChatMessage> afterMicro = micro_compact(fullHistory);

        switch (strategy) {
            case SLIDING_WINDOW:
                result = applySlidingWindow(afterMicro);
                break;
            case TOKEN_BASED:
                result = applyTokenLimit(fullHistory,afterMicro);
                break;
            case HYBRID:
                result = applyHybridStrategy(fullHistory,afterMicro);
                break;
            default:
                result = afterMicro;
        }

        // 输出统计信息
        logContextStatistics(fullHistory, result);

        return result;
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

            context.append("##  工具调用规范（重要！你必须严格遵守！）\n\n");
            context.append("你可以调用以下工具来执行操作。**必须使用模型的原生 Tool Calling，不要在文本里拼接命令字符串。**\n\n");

            context.append("可用工具：\n");
            context.append("- 文件管理：`file_manager(command, path, content?)`（read/write/list/create/delete/info）\n");
            context.append("- 执行命令：`command_executor(command)`\n");
            context.append("- 代码执行：`code_executor(...)`\n");
            context.append("- 文本检索：`grep_search(query, includePattern?, isRegexp?)`\n\n");

            context.append("###  重要：根据用户实际需求生成命令\n\n");
            context.append("**不要使用固定示例，要根据用户的实际请求生成正确的命令！**\n\n");

            context.append("**用户请求 → 正确的工具调用：**\n");
            context.append("- \"查看桌面有哪些文件\" → `file_manager(command=\"list\", path=\"~/Desktop\")` 或 `command_executor(command=\"ls -la ~/Desktop\")`\n");
            context.append("- \"查看当前目录\" → `file_manager(command=\"list\", path=\".\")` 或 `command_executor(command=\"ls -la\")`\n");
            context.append("- \"查看sessions目录\" → `file_manager(command=\"list\", path=\"sessions\")` 或 `command_executor(command=\"ls -la sessions\")`\n");
            context.append("- \"读取桌面的test.txt\" → `file_manager(command=\"read\", path=\"~/Desktop/test.txt\")`\n");
            context.append("- \"删除桌面的demo.java\" → `command_executor(command=\"rm ~/Desktop/demo.java\")` 或 `file_manager(command=\"delete\", path=\"~/Desktop/demo.java\")`\n\n");

            context.append("! **工具调用的关键规则（必须遵守！）：**\n");
            context.append("1. **工具调用会被立即执行** - 系统会自动处理调用\n");
            context.append("2. **不要输出伪命令文本** - 只发起原生工具调用\n");
            context.append("3. **调用工具后停止推断结果** - 等待系统返回真实输出\n");
            context.append("4. **绝对禁止编造工具结果** - 你不知道目录里有什么文件，不知道命令执行结果，**绝对不能猜测或编造**\n");
            context.append("5. **等待工具执行** - 系统会执行工具并返回真实结果给你，然后你才能继续回答\n");
            context.append("6. **提供选项时不执行** - 当你列出选项（1. 2. 3. 4.）时，不要执行任何操作，等待用户选择\n\n");

            context.append("! **严格禁止的错误行为示例：**\n");
            context.append(" 错误示例1：调用目录查询后，立刻在文本中编造目录内容。\n");
            context.append(" 正确示例1：发起 `file_manager(command=\"list\", path=\"sessions\")` 调用后等待返回。\n\n");

            context.append(" 错误示例2：调用删除命令后，立刻在文本中宣称删除成功。\n");
            context.append(" 正确示例2：发起 `command_executor(command=\"rm sessions/*\")` 调用后等待返回。\n\n");

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

    private List<ChatMessage> micro_compact(List<ChatMessage> messages) {
        // sessions中保存结果不变，不可修改messages，使用深拷贝：创建全新消息对象
        List<ChatMessage> result = new ArrayList<>();
        int lastUserIndex = -1;

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            result.add(new ChatMessage(msg)); // 使用复制构造器
            // 记录最近一轮对话的起点索引（最后一个 user 消息）
            if ("user".equals(msg.getRole())) {
                lastUserIndex = i;
            }
        }

        int SAFE_TOOL_LENGTH = 500;
        int HEAD_TAIL_SIZE = 200;

        for (int i = 0; i < result.size(); i++) {
            ChatMessage msg = result.get(i);
            // 仅对最近一轮对话（最后一个 user 消消息）之前的工具调用结果进行特征提取压缩
            if (i < lastUserIndex && msg != null && "system".equals(msg.getRole()) && isToolResultMessage(msg.getContent())) {
                String content = msg.getContent();
                if (content != null && content.length() > SAFE_TOOL_LENGTH) {
                    String toolName = extractToolNameFromContent(content);
                    String compactedContent;

                    if ("file_manager".equals(toolName) || "command_executor".equals(toolName) || "grep_search".equals(toolName)) {
                        String head = content.substring(0, HEAD_TAIL_SIZE);
                        String tail = content.substring(content.length() - HEAD_TAIL_SIZE);
                        int omittedLength = content.length() - (HEAD_TAIL_SIZE * 2);
                        compactedContent = String.format("%s\n\n[... omitted %d chars, tool: %s ...]\n\n%s",
                                head, omittedLength, toolName, tail);
                    } else {
                        compactedContent = String.format("%s\n\n[... content truncated for token saving. Tool: %s ...]",
                                content.substring(0, SAFE_TOOL_LENGTH), toolName);
                    }
                    msg.setContent(compactedContent);
                }
            }
        }

        return result;
    }

    private boolean isToolResultMessage(String content) {
        if (content == null) return false;
        // 工具成功或失败消息的特征前缀
        return content.startsWith("Tool '") || content.startsWith("Tool execution failed: ");
    }

    // 从消息内容中提取工具名称
    // 格式示例: "Tool 'file_manager' executed successfully ..." 或 "Tool execution failed: 'unknown_tool' not found."
    private String extractToolNameFromContent(String content) {
        try {
            // 查找单引号之间的内容
            int start = content.indexOf('\'');
            int end = content.indexOf('\'', start + 1);
            if (start != -1 && end != -1) {
                return content.substring(start + 1, end);
            }
            // 降级处理：尝试匹配 "Tool execution failed: " 后的内容
            if (content.startsWith("Tool execution failed: ")) {
                String after = content.substring("Tool execution failed: ".length());
                int space = after.indexOf(' ');
                if (space > 0) {
                    return after.substring(0, space);
                }
                return "unknown";
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 策略1：滑动窗口
     * 保留最近 N 轮对话
     */
    private List<ChatMessage> applySlidingWindow(List<ChatMessage> fullHistory) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return new ArrayList<>();
        }

        // maxHistoryTurns 现在真实代表“保留多少个 User 的问题回合”
        int remainTurns = maxHistoryTurns;

        int boundaryIndex = 0; // 默认保留全部
        int userMessageCount = 0;

        // 从后往前扫，数遇到了几个 "user" 消息
        for (int i = fullHistory.size() - 1; i >= 0; i--) {
            ChatMessage msg = fullHistory.get(i);
            if ("user".equals(msg.getRole())) {
                userMessageCount++;
                // 当找到了正好是保留上限的那个 user 消息，记录索引并跳出
                if (userMessageCount == remainTurns) {
                    boundaryIndex = i;
                    break;
                }
            }
        }

        return new ArrayList<>(fullHistory.subList(boundaryIndex, fullHistory.size()));
    }

    /**
     * 策略2：Token 控制
     * 根据 Token 数量动态截断
     */
    private List<ChatMessage> applyTokenLimit(List<ChatMessage> fullHistory,List<ChatMessage> afterMicro) {
        if (!shouldCompressHistory()) {
            return afterMicro;
        }

        try{
            // 1. 生成对话文本
            // 精准切分：保留最后 2 条记录（通常是最后一轮 User 问 + AI 答）
            int keepCount = Math.min(2, fullHistory.size());
            int splitIndex = fullHistory.size() - keepCount;

            List<ChatMessage> toSummarize = fullHistory.subList(0, splitIndex);
            List<ChatMessage> tailMessages = new ArrayList<>(fullHistory.subList(splitIndex, fullHistory.size()));

            String conversationText = truncateConversation(toSummarize);

            // 2. 构建摘要 prompt
            String prompt = "Summarize this conversation for continuity. Include: " +
                    "1) What was accomplished, 2) Current state, 3) Key decisions made. " +
                    "Be concise but preserve critical details.\n\n" + conversationText;

            // 3. 调用 LLM 生成摘要
            String summary = callLlmForSummary(prompt);

            // 4. 构建压缩后的消息列表
            String sessionId = afterMicro.get(0).getSessionId();
            fullHistory.clear();

            // 构建新的消息历史
            fullHistory.add(new ChatMessage("user",
                    "[Conversation compressed.]" + "\n\n" + summary,sessionId));

            // 重新接上尾部对话，保证上下文连贯
            fullHistory.addAll(tailMessages);

            resetTokenUsage();
            return fullHistory;
        } catch (Exception e){
            return afterMicro;
        }
    }

    /**
     * 策略3：混合策略
     * 先应用滑动窗口，再应用 Token 控制
     */
    private List<ChatMessage> applyHybridStrategy(List<ChatMessage> fullHistory,List<ChatMessage> afterMicro) {
        // 1. 先应用滑动窗口
        List<ChatMessage> windowedHistory = applySlidingWindow(fullHistory);

        // 2. 再应用 Token 控制
        return applyTokenLimit(windowedHistory,afterMicro);
    }

    /**
     * 估算文本的 Token 数量
     * 简单方法：中文 2 字符 ≈ 1 token，英文 4 字符 ≈ 1 token
     *
     * @param text 待估算的文本
     * @return 估算的 token 数量
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int chineseChars = 0;
        int otherChars = 0;

        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }

        // 中文：2 字符 ≈ 1 token
        // 英文：4 字符 ≈ 1 token
        return (chineseChars / 2) + (otherChars / 4);
    }

    /**
     * 判断字符是否为中文
     */
    private boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    /**
     * 截断文本到指定 Token 限制
     */
    private String truncateToTokenLimit(String text, int maxTokens) {
        if (estimateTokens(text) <= maxTokens) {
            return text;
        }

        // 简单截断：取前 N 个字符
        int targetChars = maxTokens * 3;  // 保守估计
        if (text.length() <= targetChars) {
            return text;
        }

        return text.substring(0, targetChars) + "\n\n[内容过长已截断...]";
    }

    /**
     * 输出上下文统计信息
     */
    private void logContextStatistics(List<ChatMessage> fullHistory, List<ChatMessage> managedHistory) {
        if (lastHistoryTokens < 0) {
            return;
        }

        int threshold = getCompressionThreshold();
        boolean compressed = managedHistory.size() < fullHistory.size();
        
        // 使用 INFO 级别日志，便于观察
        log.info("📊 上下文管理统计:");
        log.info("  策略: {}", strategy);
        log.info("  历史消息 tokens: {}", lastHistoryTokens);
        log.info("  压缩阈值: {} (maxContext {} - maxOutput {})", threshold, maxContextTokens, maxOutputTokens);
        log.info("  是否需要压缩: {}", shouldCompressHistory());
        log.info("  历史消息数量: {} 条 → 发送历史数量: {} 条", fullHistory.size(), managedHistory.size());
        
        if (compressed) {
            double compressionRate = (1 - (double)managedHistory.size() / fullHistory.size()) * 100;
            log.info("  ✅ 已压缩！压缩率: {:.2f}%", compressionRate);
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

    public synchronized void recordTokenUsage(int promptTokens, int completionTokens, int totalTokens) {
        if (promptTokens <= 0 && totalTokens <= 0) {
            return;
        }

        lastPromptTokens = promptTokens;
        lastCompletionTokens = completionTokens;
        if (totalTokens > 0) {
            lastTotalTokens = totalTokens;
        } else {
            // 如果 totalTokens 没有提供，则根据 promptTokens 和 completionTokens 估算
            lastTotalTokens = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        }
        lastHistoryTokens = lastTotalTokens;
        
        // 记录token使用情况
        log.info("🔢 Token使用记录: prompt={}, completion={}, total={}", 
            promptTokens, completionTokens, lastTotalTokens);
        log.info("  压缩阈值: {}, 是否触发压缩: {}", 
            getCompressionThreshold(), shouldCompressHistory());
    }

    public synchronized void resetTokenUsage() {
        lastHistoryTokens = -1;
        lastPromptTokens = -1;
        lastCompletionTokens = -1;
        lastTotalTokens = -1;
    }

    // 获取当前历史消息的 Token 数量
    int getCompressionThreshold() {
        return maxContextTokens - maxOutputTokens;
    }

    // 判断是否需要压缩历史消息
    boolean shouldCompressHistory() {
        int threshold = getCompressionThreshold();
        if (lastHistoryTokens < 0 || threshold <= 0) {
            return false;
        }
        return lastHistoryTokens >= threshold;
    }

    /**
     * 获取当前策略
     */
    public Strategy getStrategy() {
        return strategy;
    }

    /**
     * 设置策略
     */
    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
        log.info("切换上下文策略为: {}", strategy);
    }

    /**
     * 设置最大历史轮数（用于滑动窗口策略）
     */
    public void setMaxHistoryTurns(int maxHistoryTurns) {
        this.maxHistoryTurns = maxHistoryTurns;
        log.info("设置最大历史轮数: {} 轮", maxHistoryTurns);
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
        return String.format("Strategy: %s, MaxTurns: %d, MaxTokens: %d",
                strategy, maxHistoryTurns, maxContextTokens);
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
}



