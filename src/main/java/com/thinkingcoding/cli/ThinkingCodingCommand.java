package com.thinkingcoding.cli;

import com.thinkingcoding.agentloop.v2.orchestrator.AgentConfig;
import com.thinkingcoding.agentloop.v2.orchestrator.AgentOrchestrator;
import com.thinkingcoding.core.AgentLoop;
import com.thinkingcoding.core.DirectCommandExecutor;
import com.thinkingcoding.core.ThinkingCodingContext;
import com.thinkingcoding.model.ChatMessage;
import com.thinkingcoding.service.SessionService;
import com.thinkingcoding.ui.ThinkingCodingUI;
import picocli.CommandLine;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * 命令解析和路由
 *
 * 参数解析：Picocli自动将 --interactive 等参数映射到字段
 *
 * 命令路由：根据参数组合决定执行路径
 *
 * 上下文传递：确保所有命令都能访问统一的上下文
 *
 * 服务获取和准备，通过Context统一获取服务实例 (Command类从Context获取服务)
 */
@CommandLine.Command(name = "thinking", mixinStandardHelpOptions = true,
        description = "ThinkingCoding CLI - Interactive Code Assistant")
public class ThinkingCodingCommand implements Callable<Integer> {

    private final ThinkingCodingContext context;

    // 添加会话管理字段
    private AgentLoop currentAgentLoop;
    private AgentOrchestrator currentAgentOrchestrator;  // 🔥 V2 AgentLoop
    private String currentSessionId;

    // 直接命令执行器
    private DirectCommandExecutor directCommandExecutor;

    @CommandLine.Option(names = {"-i", "--interactive"}, description = "Run in interactive mode")
    private boolean interactive = true;

    @CommandLine.Option(names = {"-c", "--continue"}, description = "Continue last session")
    private boolean continueSession;

    @CommandLine.Option(names = {"-S", "--session"}, description = "Specify session ID")
    private String sessionId;

    @CommandLine.Option(names = {"-p", "--prompt"}, description = "Single prompt mode")
    private String prompt;

    @CommandLine.Option(names = {"-m", "--model"}, description = "Specify model to use")
    private String model;

    @CommandLine.Option(names = {"--list-sessions"}, description = "List all sessions")
    private boolean listSessions;

    @CommandLine.Option(names = {"--delete-session"}, description = "Delete specified session")
    private String deleteSessionId;

    // 🔥 新增 MCP 选项
    @CommandLine.Option(names = {"--mcp-tools"}, description = "Use predefined MCP tools (e.g., github-search,file-system,sql-query)")
    private String mcpTools;

    @CommandLine.Option(names = {"--mcp-connect"}, description = "Connect to custom MCP server")
    private String mcpConnect;

    @CommandLine.Option(names = {"--mcp-command"}, description = "MCP server command")
    private String mcpCommand;

    @CommandLine.Option(names = {"--mcp-list"}, description = "List available MCP tools and servers")
    private boolean mcpList;

    @CommandLine.Option(names = {"--mcp-predefined"}, description = "List predefined MCP tools")
    private boolean mcpPredefined;

    // 🔥 V2 AgentLoop 选项
    @CommandLine.Option(names = {"--agent-loop"}, description = "AgentLoop version: legacy|v2 (default: v2)")
    private String agentLoopVersion = "v2";

    @CommandLine.Option(names = {"--auto-approve"}, description = "Enable auto-approve mode for tools (V2 only)")
    private boolean autoApprove = false;

    public ThinkingCodingCommand(ThinkingCodingContext context) {
        this.context = context;
        this.directCommandExecutor = new DirectCommandExecutor(context);
    }

    @Override
    public Integer call() {
        try {
            // 🔥 先处理 MCP 选项（在初始化上下文之前）
            handleMCPOptions();

            ThinkingCodingUI ui = context.getUi();
            SessionService sessionService = context.getSessionService();

            // 显示欢迎信息
            ui.showBanner();

            // 🔥 显示 MCP 状态信息
            if (context.isMCPEnabled() || mcpTools != null || mcpConnect != null) {
                int mcpToolCount = context.getMCPToolCount();
                if (mcpToolCount > 0) {
                    ui.displaySuccess("MCP Tools: " + mcpToolCount + " tools available");
                }
            }

            // 确定使用哪个模型
            String modelToUse = model != null ? model : context.getAppConfig().getDefaultModel();

            // 处理列表会话
            if (listSessions) {
                List<String> sessions = sessionService.listSessions();
                ui.displaySessionList(sessions);
                return 0;
            }

            // 处理删除会话
            if (deleteSessionId != null) {
                boolean deleted = sessionService.deleteSession(deleteSessionId);
                if (deleted) {
                    ui.displayInfo("Session deleted: " + deleteSessionId);
                } else {
                    ui.displayError("Failed to delete session: " + deleteSessionId);
                }
                return 0;
            }

            // 🔥 处理 MCP 列表命令
            if (mcpList) {
                context.printMCPInfo();
                return 0;
            }

            // 🔥 处理预定义 MCP 工具列表
            if (mcpPredefined) {
                showPredefinedMCPTools();
                return 0;
            }

            // 加载会话历史
            List<ChatMessage> history = new ArrayList<>();

            if (continueSession) {
                currentSessionId = sessionService.getLatestSessionId();
                if (currentSessionId != null) {
                    history = sessionService.loadSession(currentSessionId);
                    ui.displayInfo("Continuing from previous session: " + currentSessionId);
                } else {
                    ui.displayInfo("No previous session found. Starting new session.");
                    currentSessionId = UUID.randomUUID().toString();
                }
            } else if (sessionId != null) {
                currentSessionId = sessionId;
                history = sessionService.loadSession(sessionId);
                ui.displayInfo("Loaded session: " + sessionId);
            } else {
                // 创建新会话
                currentSessionId = UUID.randomUUID().toString();
                ui.displayInfo("Created new session: " + currentSessionId);
            }

            // 创建Agent循环
            // AgentLoop启动和协调 ，AI对话、工具调用、会话管理等
            /*流程协调：管理从用户输入到AI响应的完整流程

            工具调度：协调AI模型与工具系统的交互

            状态管理：维护对话状态和上下文

            错误处理：处理整个流程中的异常情况*/
            createAgentLoop(modelToUse, history);

            // 单次对话模式
            if (prompt != null) {
                try {

                    // 显示用户输入
                    ChatMessage userMessage = new ChatMessage("user", prompt);
                    ui.displayUserMessage(userMessage);

                    // 处理AI响应（版本已在 createAgentLoop 中确定）
                    if (currentAgentOrchestrator != null) {
                        currentAgentOrchestrator.processInput(prompt);
                    } else {
                        currentAgentLoop.processInput(prompt);
                    }

                    return 0;
                } catch (Exception e) {
                    context.getUi().displayError("Failed to process prompt: " + e.getMessage());
                    e.printStackTrace();
                    return 1;
                }
            }

            // Picocli自动解析命令行参数并注入到字段中
            // 交互式模式
            if (interactive) {
                return startInteractiveMode(ui);
            }

            return 0;

        } catch (Exception e) {
            context.getUi().displayError("Error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 🔥 处理 MCP 选项
     */
    private void handleMCPOptions() {
        ThinkingCodingUI ui = context.getUi();

        if (mcpTools != null) {
            ui.displayInfo("Connecting predefined MCP tools: " + mcpTools);
            boolean success = context.usePredefinedMCPTools(mcpTools);
            if (success) {
                ui.displaySuccess("MCP tools connected successfully");
            } else {
                ui.displayError("Failed to connect MCP tools");
            }
        }

        if (mcpConnect != null && mcpCommand != null) {
            ui.displayInfo("Connecting MCP server: " + mcpConnect);
            boolean success = context.connectMCPServer(mcpConnect, mcpCommand, Collections.emptyList());
            if (success) {
                ui.displaySuccess("MCP server connected successfully");
            } else {
                ui.displayError("Failed to connect MCP server");
            }
        }
    }

    /**
     * 🔥 显示预定义 MCP 工具列表
     */
    private void showPredefinedMCPTools() {
        ThinkingCodingUI ui = context.getUi();
        ui.getTerminal().writer().println("\n🔧 Predefined MCP Tools:");
        ui.getTerminal().writer().println("──────────────────────");
        ui.getTerminal().writer().println("• github-search    - GitHub repository search");
        ui.getTerminal().writer().println("• sql-query        - PostgreSQL database queries");
        ui.getTerminal().writer().println("• file-system      - Local file system operations");
        ui.getTerminal().writer().println("• web-search       - Web search using Brave");
        ui.getTerminal().writer().println("• calculator       - Mathematical calculations");
        ui.getTerminal().writer().println("• weather          - Weather information");
        ui.getTerminal().writer().println("• memory           - Memory operations");
        ui.getTerminal().writer().println();
        ui.getTerminal().writer().println("Usage: --mcp-tools tool1,tool2,tool3");
        ui.getTerminal().writer().flush();
    }

    private Integer startInteractiveMode(ThinkingCodingUI ui) {
        if (currentAgentOrchestrator != null) {
            ui.displaySuccess("✅ AgentLoop V2 已启用");
            ui.displayInfo("   架构: Plan → Execute + ReAct → Steering");
            ui.displayInfo("   - Auto Approve: " + (autoApprove ? "ON" : "OFF"));
            ui.displayInfo("   - 支持命令: /stop, /cancel, /auto-approve-on/off");
        } else {
            ui.displayInfo("使用 Legacy AgentLoop");
        }
        
        ui.displayInfo("Entering interactive mode. Type 'exit' to quit, 'help' for commands.");

        while (true) {
            try {
                // 🔥 在读取输入前输出一个换行，确保 thinking> 提示符在新的一行
                ui.getTerminal().writer().println();
                ui.getTerminal().writer().flush();

                String input = ui.readInput("thinking> ");

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                String trimmedInput = input.trim();

                // 退出命令
                if (trimmedInput.equalsIgnoreCase("exit") || trimmedInput.equalsIgnoreCase("quit")) {
                    // 设置UI回调
                    ui.displayInfo("Goodbye!");
                    break;
                }

                // 帮助命令
                if (trimmedInput.equalsIgnoreCase("help")) {
                    showHelp();
                    continue;
                }

                // 清屏命令
                if (trimmedInput.equalsIgnoreCase("clear")) {
                    ui.clearScreen();
                    continue;
                }

                // 🛑 停止生成命令
                if (trimmedInput.equalsIgnoreCase("stop") || trimmedInput.equalsIgnoreCase("停止")) {
                    stopCurrentGeneration();
                    continue;
                }

                // Steering 命令（V2 支持，V1 返回 false）
                if (currentAgentOrchestrator != null && currentAgentOrchestrator.handleSteeringCommand(trimmedInput)) {
                    continue;
                }

                // 🔧 直接命令帮助
                if (trimmedInput.equalsIgnoreCase("/commands") || trimmedInput.equalsIgnoreCase("/cmds")) {
                    directCommandExecutor.listSupportedCommands();
                    continue;
                }

                // 🔥 MCP 相关命令 - 直接在这里处理
                if (trimmedInput.startsWith("/mcp")) {
                    handleMCPCommand(trimmedInput);
                    continue;
                }

                // 🚀 新增：检查是否是直接命令执行
                if (directCommandExecutor.shouldExecuteDirectly(trimmedInput)) {
                    directCommandExecutor.executeDirectCommand(trimmedInput);
                    continue;
                }

                // 🚨 关键修复：检查是否是参数格式
                if (isParameterFormat(trimmedInput)) {
                    handleParameterInInteractiveMode(trimmedInput);
                    continue;
                }

                // 处理普通对话（版本已在 createAgentLoop 中确定）
                if (currentAgentOrchestrator != null) {
                    currentAgentOrchestrator.processInput(trimmedInput);
                } else {
                    currentAgentLoop.processInput(trimmedInput);
                }

            } catch (Exception e) {
                ui.displayError("Error: " + e.getMessage());
            }
        }

        return 0;
    }

// 删除 handleInternalCommand 方法，因为我们已经直接处理了 MCP 命令
// 删除 handleSinglePrompt 方法，因为单次提示模式已经在 call() 方法中处理了

    /**
     * 🔥 创建 AgentLoop（支持 legacy/v2 切换）
     */
    private void createAgentLoop(String modelToUse, List<ChatMessage> history) {
        ThinkingCodingUI ui = context.getUi();
        
        if ("v2".equalsIgnoreCase(agentLoopVersion)) {
            // 启用 V2
            try {
                // 配置 V2
                AgentConfig config = AgentConfig.defaultConfig();
                config.setEnabled(true);
                config.setAutoApproveDefault(autoApprove);
                
                // 创建 V2 AgentOrchestrator
                currentAgentOrchestrator = new AgentOrchestrator(context, currentSessionId, modelToUse, config);
                currentAgentOrchestrator.loadHistory(history);
                
                ui.displaySuccess("✅ AgentLoop V2 初始化成功");
            } catch (Exception e) {
                ui.displayError("❌ V2 初始化失败: " + e.getMessage());
                ui.displayWarning("⚠️  回退到 Legacy AgentLoop");
                
                // 回退到 Legacy
                currentAgentLoop = new AgentLoop(context, currentSessionId, modelToUse);
                currentAgentLoop.loadHistory(history);
            }
        } else {
            // 使用 Legacy
            currentAgentLoop = new AgentLoop(context, currentSessionId, modelToUse);
            currentAgentLoop.loadHistory(history);
        }
    }

    /**
     * 🔥 处理 MCP 相关命令
     */
    private void handleMCPCommand(String command) {
        String[] parts = command.substring(4).trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1] : "";

        ThinkingCodingUI ui = context.getUi();

        switch (cmd) {
            case "list":
                context.printMCPInfo();
                break;
            case "connect":
                if (!argument.isEmpty()) {
                    String[] connectArgs = argument.split("\\s+");
                    if (connectArgs.length >= 1) {
                        String serverName = connectArgs[0];
                        String serverCommand = connectArgs.length >= 2 ? connectArgs[1] : getDefaultNpxCommand();

                        // 🔥 根据服务器名自动构建参数
                        List<String> args = buildMCPArgs(serverName);

                        boolean success = context.connectMCPServer(serverName, serverCommand, args);
                        if (success) {
                            ui.displaySuccess("MCP server connected: " + serverName);
                        } else {
                            ui.displayError("Failed to connect MCP server");
                        }
                    } else {
                        ui.displayError("Usage: /mcp connect <server-name> [command]");
                        ui.displayInfo("Example: /mcp connect filesystem");
                        ui.displayInfo("         /mcp connect filesystem npx");
                    }
                } else {
                    ui.displayError("Usage: /mcp connect <server-name> [command]");
                }
                break;
            case "tools":
                if (!argument.isEmpty()) {
                    boolean success = context.usePredefinedMCPTools(argument);
                    if (success) {
                        ui.displaySuccess("MCP tools connected: " + argument);
                    } else {
                        ui.displayError("Failed to connect MCP tools");
                    }
                } else {
                    ui.displayError("Usage: /mcp tools <tool1,tool2,tool3>");
                }
                break;
            case "disconnect":
                if (!argument.isEmpty()) {
                    context.disconnectMCPServer(argument);
                    ui.displaySuccess("MCP server disconnected: " + argument);
                } else {
                    ui.displayError("Usage: /mcp disconnect <server-name>");
                }
                break;
            case "predefined":
                showPredefinedMCPTools();
                break;
            default:
                ui.displayError("Unknown MCP command: " + cmd);
                ui.displayInfo("Available MCP commands:");
                ui.displayInfo("  /mcp list                    - List connected MCP tools");
                ui.displayInfo("  /mcp connect <name> <cmd>    - Connect to MCP server");
                ui.displayInfo("  /mcp tools <tool1,tool2>     - Use predefined tools");
                ui.displayInfo("  /mcp disconnect <name>       - Disconnect MCP server");
                ui.displayInfo("  /mcp predefined              - Show predefined tools");
                break;
        }
    }

    /**
     * 检查输入是否是参数格式（以 - 或 -- 开头）
     */
    private boolean isParameterFormat(String input) {
        return input.startsWith("-") && input.length() > 1;
    }

    /**
     * 在交互模式中处理参数命令
     */
    private void handleParameterInInteractiveMode(String parameter) {
        ThinkingCodingUI ui = context.getUi();
        String[] args = parameter.split("\\s+");

        switch (args[0]) {
            case "-i":
            case "--interactive":
                ui.displayInfo("Already in interactive mode");
                break;

            case "-c":
            case "--continue":
                handleContinueInInteractive();
                break;

            case "-S":
            case "--session":
                if (args.length > 1) {
                    handleLoadSession(args[1]);
                } else {
                    ui.displayError("Usage: -S <session-id>");
                }
                break;

            case "-p":
            case "--prompt":
                if (args.length > 1) {
                    // 重新组合提示内容（处理带空格的提示）
                    String prompt = parameter.substring(args[0].length()).trim();

                    // 🚨 关键修复：去掉引号
                    if (prompt.startsWith("\"") && prompt.endsWith("\"")) {
                        prompt = prompt.substring(1, prompt.length() - 1);
                    }

                    if (!prompt.isEmpty()) {
                        handleSinglePromptInInteractive(prompt);
                    } else {
                        ui.displayError("Usage: -p \"your prompt\"");
                    }
                } else {
                    ui.displayError("Usage: -p \"your prompt\"");
                }
                break;

            // 🔥 新增 MCP 参数处理
            case "--mcp-tools":
                if (args.length > 1) {
                    boolean success = context.usePredefinedMCPTools(args[1]);
                    if (success) {
                        ui.displaySuccess("MCP tools connected: " + args[1]);
                    } else {
                        ui.displayError("Failed to connect MCP tools");
                    }
                } else {
                    ui.displayError("Usage: --mcp-tools <tool1,tool2,tool3>");
                }
                break;

            case "--mcp-list":
                context.printMCPInfo();
                break;

            case "--mcp-predefined":
                showPredefinedMCPTools();
                break;

            default:
                ui.displayError("Unknown parameter: " + args[0]);
                break;
        }
    }

    /**
     * 在交互模式中继续上次会话
     */
    private void handleContinueInInteractive() {
        ThinkingCodingUI ui = context.getUi();
        try {
            //Command从 Context获取服务
            String latestSessionId = context.getSessionService().getLatestSessionId();
            if (latestSessionId != null) {
                List<ChatMessage> history = context.getSessionService().loadSession(latestSessionId);
    
                // 🚨 关键修复：过滤空消息
                List<ChatMessage> filteredHistory = history.stream()
                        .filter(msg -> msg != null &&
                                msg.getContent() != null &&
                                !msg.getContent().trim().isEmpty())
                        .collect(Collectors.toList());
    
                if (currentAgentOrchestrator != null) {
                    currentAgentOrchestrator.loadHistory(filteredHistory);
                } else if (currentAgentLoop != null) {
                    currentAgentLoop.loadHistory(filteredHistory);
                }
                    
                currentSessionId = latestSessionId;
                ui.displaySuccess("Continued from session: " + latestSessionId);
    
                // 显示加载的消息数量
                ui.displayInfo("Loaded " + filteredHistory.size() + " messages from history");
            } else {
                ui.displayInfo("No previous session found.");
            }
        } catch (Exception e) {
            ui.displayError("Failed to continue session: " + e.getMessage());
        }
    }
    
    /**
     * 在交互模式中加载指定会话
     */
    private void handleLoadSession(String sessionId) {
        ThinkingCodingUI ui = context.getUi();
        try {
            List<ChatMessage> history = context.getSessionService().loadSession(sessionId);
                
            if (currentAgentOrchestrator != null) {
                currentAgentOrchestrator.loadHistory(history);
            } else if (currentAgentLoop != null) {
                currentAgentLoop.loadHistory(history);
            }
                
            currentSessionId = sessionId;
            ui.displaySuccess("Loaded session: " + sessionId);
        } catch (Exception e) {
            ui.displayError("Failed to load session: " + e.getMessage());
        }
    }
    
    /**
     * 在交互模式中执行单次提示
     */
    private void handleSinglePromptInInteractive(String prompt) {
        ThinkingCodingUI ui = context.getUi();
        try {
            ui.displayUserMessage(new ChatMessage("user", prompt));
                
            if (currentAgentOrchestrator != null) {
                currentAgentOrchestrator.processInput(prompt);
            } else if (currentAgentLoop != null) {
                currentAgentLoop.processInput(prompt);
            }
        } catch (Exception e) {
            ui.displayError("Failed to process prompt: " + e.getMessage());
        }
    }

    /**
     * 🛑 停止当前的 AI 生成
     */
    private void stopCurrentGeneration() {
        ThinkingCodingUI ui = context.getUi();
        try {
            // 尝试停止 LangChainService 的生成
            if (context.getAiService() instanceof com.thinkingcoding.service.LangChainService) {
                com.thinkingcoding.service.LangChainService langChainService =
                    (com.thinkingcoding.service.LangChainService) context.getAiService();

                if (langChainService.isGenerating()) {
                    langChainService.stopCurrentGeneration();
                    ui.displayWarning("⏸️  生成已停止");
                } else {
                    ui.displayInfo("ℹ️  当前没有正在进行的生成");
                }
            } else {
                ui.displayWarning("⚠️  当前 AI 服务不支持停止功能");
            }
        } catch (Exception e) {
            ui.displayError("停止生成时出错: " + e.getMessage());
        }
    }

    private void showHelp() {
        context.getUi().displayInfo("""
                        🚀 可用命令：
                                                               \s
                                                                💬 对话命令：
                                                                  <消息>         发送消息给AI助手
                                                                  stop / 停止   停止当前的AI生成
                                                                  /new          开始新会话
                                                                  /save <名称>  保存当前会话
                                                                  /list         查看所有会话
                                                                  /clear        清空屏幕
                                                                  /help         显示帮助信息
                                                               \s
                                                                🔧 直接命令：
                                                                  java version  直接执行Java命令
                                                                  git status    直接执行Git命令
                                                                  pwd, ls, etc. 系统命令直接执行
                                                                  /commands     查看所有支持直接执行的命令
                                                               \s
                                                                🔧 MCP 命令：
                                                                  /mcp list             列出MCP工具
                                                                  /mcp connect <n> <c>  连接MCP服务器
                                                                  /mcp tools <t1,t2>    使用预定义工具
                                                                  /mcp disconnect <n>   断开MCP服务器
                                                                  /mcp predefined       显示预定义工具
                                                               \s
                                                                ⚡ 快捷命令：
                                                                  -c, --continue       继续上次会话
                                                                  -S <id>             加载指定会话 \s
                                                                  -p "提示"           单次提问模式
                                                                  --list-sessions     列出所有会话
                                                                  --mcp-tools <t>     使用MCP工具
                                                                  --mcp-list          列出MCP状态
                                                               \s
                                                                ❌ 退出命令：
                                                                  exit / quit         退出程序
                """);
    }

    /**
     * 🔥 根据服务器名从配置文件读取 MCP 参数
     * 优先使用配置文件中的配置，如果没有则使用默认配置
     */
    private List<String> buildMCPArgs(String serverName) {
        // 🔥 优先从配置文件读取
        var mcpConfig = context.getMcpConfig();
        var serverConfig = mcpConfig.getServerConfig(serverName);

        if (serverConfig != null && serverConfig.getArgs() != null && !serverConfig.getArgs().isEmpty()) {
            // 使用配置文件中的参数
            context.getUi().displayInfo("✓ 使用配置文件中的 " + serverName + " 配置");
            return new ArrayList<>(serverConfig.getArgs());
        }

        // 🔥 如果配置文件中没有，使用默认配置（向后兼容）
        context.getUi().displayWarning("⚠ 配置文件中未找到 " + serverName + " 配置，使用默认配置");

        List<String> args = new ArrayList<>();

        switch (serverName.toLowerCase()) {
            case "filesystem":
            case "file-system":
                args.add("-y");
                args.add("@modelcontextprotocol/server-filesystem");
                args.add(System.getProperty("user.home")); // 用户主目录
                break;

            case "sqlite":
                args.add("-y");
                args.add("@modelcontextprotocol/server-sqlite");
                args.add("--database");
                args.add("./data.db");
                break;

            case "postgres":
            case "postgresql":
                args.add("-y");
                args.add("@modelcontextprotocol/server-postgres");
                args.add("--connectionString");
                args.add("postgresql://user:pass@localhost:5432/db");
                break;

            case "github":
                args.add("-y");
                args.add("@modelcontextprotocol/server-github");
                args.add("--token");
                args.add("your_github_token_here");  // 占位符
                break;

            case "mysql":
                args.add("-y");
                args.add("@modelcontextprotocol/server-mysql");
                args.add("--connectionString");
                args.add("mysql://user:pass@localhost:3306/db");
                break;

            case "weather":
                args.add("-y");
                args.add("@modelcontextprotocol/server-weather");
                args.add("--apiKey");
                args.add("your_weather_api_key");
                break;

            default:
                // 如果是未知服务器，尝试作为包名直接安装
                args.add("-y");
                args.add(serverName);
                break;
        }

        return args;
    }

    private String getDefaultNpxCommand() {
        String osName = System.getProperty("os.name");
        if (osName != null && osName.toLowerCase(Locale.ROOT).contains("windows")) {
            return "npx.cmd";
        }
        return "npx";
    }

    // ... 其他方法保持不变（handleInternalCommand, handleSinglePrompt, handleNewSession, handleSaveSession, handleListSessions, displaySessionList, handleClearScreen, saveCurrentSession）
    // 这些方法不需要修改，保持原有逻辑
}

