package com.thinkingcoding.core;

import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.config.ConfigManager;
import com.thinkingcoding.config.MCPConfig;
import com.thinkingcoding.mcp.MCPService;
import com.thinkingcoding.mcp.MCPToolManager;
import com.thinkingcoding.service.AIService;
import com.thinkingcoding.service.ContextManager;
import com.thinkingcoding.service.LangChainService;
import com.thinkingcoding.service.PerformanceMonitor;
import com.thinkingcoding.service.SessionService;
import com.thinkingcoding.tools.*;
import com.thinkingcoding.tools.exec.CodeExecutorTool;
import com.thinkingcoding.tools.exec.CommandExecutorTool;
import com.thinkingcoding.tools.file.FileManagerTool;
import com.thinkingcoding.tools.rag.CodeGraphTool;
import com.thinkingcoding.tools.search.GrepSearchTool;
import com.thinkingcoding.tools.todo.AgentTodoTool;
import com.thinkingcoding.ui.ThinkingCodingUI;
import com.thinkingcoding.skill.LazySkillToolAdapter;
import com.thinkingcoding.skill.SkillContextLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 上下文初始化过程
 *
 * 依赖注入：确保各个组件都能获取到它们需要的依赖
 *
 * 生命周期管理：控制初始化顺序，避免循环依赖
 *
 * 资源配置：建立数据库连接、网络连接、文件句柄等
 */
public class ThinkingCodingContext {
    private final AppConfig appConfig;
    private final MCPConfig mcpConfig;
    private final AIService aiService;
    private final SessionService sessionService;
    private final ToolRegistry toolRegistry;
    private final ThinkingCodingUI ui;
    private final PerformanceMonitor performanceMonitor;

    // 🔥 新增 MCP 相关服务
    private final MCPService mcpService;
    private final MCPToolManager mcpToolManager;

    // 🔥 新增上下文管理器
    private final ContextManager contextManager;

    private ThinkingCodingContext(Builder builder) {
        this.appConfig = builder.appConfig;
        this.mcpConfig = builder.mcpConfig;
        this.aiService = builder.aiService;
        this.sessionService = builder.sessionService;
        this.toolRegistry = builder.toolRegistry;
        this.ui = builder.ui;
        this.performanceMonitor = builder.performanceMonitor;
        this.mcpService = builder.mcpService;
        this.mcpToolManager = builder.mcpToolManager;
        this.contextManager = builder.contextManager;
    }

    public static ThinkingCodingContext initialize() {
        // 分层初始化，确保依赖顺序正确

        // 初始化配置管理器
        ConfigManager configManager = ConfigManager.getInstance();
        configManager.initialize("config.yaml");
        AppConfig appConfig = configManager.getAppConfig();
        MCPConfig mcpConfig = configManager.getMCPConfig();

        // 能力层初始化,创建工具注册表
        ToolRegistry toolRegistry = new ToolRegistry(appConfig);

        //  创建 MCP 服务
        MCPService mcpService = new MCPService(toolRegistry);
        MCPToolManager mcpToolManager = new MCPToolManager(mcpService, mcpConfig);

        // 注册内置工具 - 传递整个 AppConfig 对象
        if (appConfig.getTools().getFileManager().isEnabled()) {
            toolRegistry.register(new FileManagerTool(appConfig));
        }

        if (appConfig.getTools().getCommandExec().isEnabled()) {
            toolRegistry.register(new CommandExecutorTool(appConfig));
        }

        if (appConfig.getTools().getCodeExecutor().isEnabled()) {
            toolRegistry.register(new CodeExecutorTool(appConfig));
        }

        if (appConfig.getTools().getSearch().isEnabled()) {
            toolRegistry.register(new GrepSearchTool(appConfig));
        }

        toolRegistry.register(new AgentTodoTool());

        if (appConfig.getTools().getCodeGraph().isEnabled()) {
            toolRegistry.register(new CodeGraphTool(appConfig, mcpService));
        }

        // 🔥 初始化 MCP 服务（如果启用）
        if (mcpConfig != null && mcpConfig.isEnabled()) {
            initializeMCPTools(appConfig, mcpConfig, mcpService, toolRegistry);
        }

        // 服务层初始化
        ContextManager contextManager = new ContextManager(appConfig);  // 🔥 创建上下文管理器
        
        // 🔥 设置可用的 skill 列表到 ContextManager
        if (appConfig.getSkills() != null && !appConfig.getSkills().isEmpty()) {
            List<AppConfig.SkillConfig> enabledSkills = appConfig.getSkills().stream()
                .filter(s -> s != null && s.isEnabled())
                .collect(Collectors.toList());
            contextManager.setAvailableSkills(enabledSkills);
        }
        
        AIService aiService = new LangChainService(appConfig, toolRegistry, contextManager);  // 🔥 注入 contextManager
        SessionService sessionService = new SessionService();
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();

        // 🔥 初始化并注册 Skills（使 AI 能够调用）- 在 AIService 创建后
        initializeSkills(appConfig, aiService, toolRegistry);

        // UI层初始化
        ThinkingCodingUI ui = new ThinkingCodingUI();

        // 构建上下文（核心层初始化）
        return new Builder()
                .appConfig(appConfig)
                .mcpConfig(mcpConfig)
                .aiService(aiService)
                .sessionService(sessionService)
                .toolRegistry(toolRegistry)
                .ui(ui)
                .performanceMonitor(performanceMonitor)
                .mcpService(mcpService)
                .mcpToolManager(mcpToolManager)
                .contextManager(contextManager)  // 🔥 添加 contextManager
                .build();
    }

    /**
     * 🔥 初始化 MCP 工具
     */
    public static void initializeMCPTools(AppConfig appConfig, MCPConfig mcpConfig, MCPService mcpService, ToolRegistry toolRegistry) {
        // 🔥 简化输出：只在最后显示汇总信息

        if (mcpConfig != null && mcpConfig.isEnabled()) {
            int totalTools = 0;
            int successServers = 0;
            List<String> connectedServers = new ArrayList<>();

            for (var serverConfig : mcpConfig.getServers()) {
                if (serverConfig.isEnabled()) {
                    try {
                        // 静默连接，不输出中间过程
                        var tools = mcpService.connectToServer(
                                serverConfig.getName(),
                                serverConfig.getCommand(),
                                serverConfig.getArgs()
                        );

                        if (!tools.isEmpty()) {
                            // 注册工具（静默）
                            for (var tool : tools) {
                                toolRegistry.register(tool);
                            }
                            totalTools += tools.size();
                            successServers++;
                            connectedServers.add(serverConfig.getName());
                        }
                    } catch (Exception e) {
                        System.err.println("❌ 无法连接到 " + serverConfig.getName() + ": " + e.getMessage());
                    }
                }
            }

            // 🔥 输出汇总信息，包含已连接的 MCP 工具名称
            if (successServers > 0) {
                System.out.println("✅ 已加载 " + totalTools + " 个工具，已连接 MCP: " + String.join(", ", connectedServers));
            }
        }
    }

    /**
     * 🔥 初始化并注册 Skills
     * 将配置的 skills 注册为 AI 可调用的工具
     */
    private static void initializeSkills(AppConfig appConfig, AIService aiService, ToolRegistry toolRegistry) {
        if (appConfig == null || appConfig.getSkills() == null || appConfig.getSkills().isEmpty()) {
            return;
        }
        int registeredCount = 0;

        for (AppConfig.SkillConfig skillConfig : appConfig.getSkills()) {
            if (skillConfig == null || !skillConfig.isEnabled()) {
                continue;
            }
            if (skillConfig.getName() == null || skillConfig.getName().isBlank()) {
                System.err.println("❌ 注册 Skill 失败: name 为空");
                continue;
            }

            try {
                LazySkillToolAdapter toolAdapter = new LazySkillToolAdapter(skillConfig, appConfig, aiService, toolRegistry);
                toolRegistry.register(toolAdapter);
                registeredCount++;

                String brief = SkillContextLoader.resolveBriefContext(skillConfig);
                String summary = brief != null ? brief : skillConfig.getDescription();
                System.out.println("✅ 已注册 Skill(元数据): " + skillConfig.getName() + " - " + summary);
            } catch (Exception e) {
                System.err.println("❌ 注册 Skill '" + skillConfig.getName() + "' 失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (registeredCount > 0) {
            System.out.println("📦 共注册 " + registeredCount + " 个 Skill 工具");
        }
    }


    /**
     * 🔥 动态连接 MCP 服务器（用于命令行调用）
     */
    public boolean connectMCPServer(String serverName, String command, List<String> args) {
        if (mcpService == null) {
            System.err.println("MCP 服务未初始化");
            return false;
        }

        try {
            // 🔥 直接传递三个参数，不再创建 Map
            var tools = mcpService.connectToServer(serverName, command, args);
            if (!tools.isEmpty()) {
                // 注册工具（静默）
                for (var tool : tools) {
                    toolRegistry.register(tool);
                }
                System.out.println("✓ 成功连接 MCP 服务器: " + serverName +
                        " (" + tools.size() + " 个工具)");
                return true;
            }
        } catch (Exception e) {
            System.err.println("✗ 连接 MCP 服务器失败: " + serverName + " - " + e.getMessage());
        }
        return false;
    }



    /**
     * 🔥 使用预定义 MCP 工具
     */
    public boolean usePredefinedMCPTools(String toolsList) {
        if (mcpToolManager == null) {
            System.err.println("MCP 工具管理器未初始化");
            return false;
        }

        try {
            var toolNames = java.util.Arrays.asList(toolsList.split(","));
            var tools = mcpToolManager.connectPredefinedTools(toolNames);
            if (!tools.isEmpty()) {
                // 注册工具（静默）
                for (var tool : tools) {
                    toolRegistry.register(tool);
                }
            }
            System.out.println("✓ 已连接 " + tools.size() + " 个预定义 MCP 工具");
            return !tools.isEmpty();
        } catch (Exception e) {
            System.err.println("✗ 连接预定义 MCP 工具失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 🔥 断开 MCP 服务器
     */
    public void disconnectMCPServer(String serverName) {
        if (mcpService != null) {
            mcpService.disconnectServer(serverName);
            System.out.println("✓ 已断开 MCP 服务器: " + serverName);
        }
    }

    /**
     * 🔥 获取 MCP 工具信息
     */
    public void printMCPInfo() {
        if (mcpService == null) {
            System.out.println("MCP 服务未初始化");
            return;
        }

        var servers = mcpService.getConnectedServers();
        var tools = mcpService.getMCPTools();

        System.out.println("MCP 服务器 (" + servers.size() + " 个):");
        servers.forEach(server -> System.out.println("  - " + server));

        System.out.println("MCP 工具 (" + tools.size() + " 个):");
        tools.forEach((name, tool) ->
                System.out.println("  - " + name + ": " + tool.getDescription())
        );
    }

    /**
     * 🔥 关闭 MCP 服务
     */
    public void shutdownMCP() {
        if (mcpService != null) {
            mcpService.shutdown();
        }
        if (mcpToolManager != null) {
            mcpToolManager.shutdown();
        }
        System.out.println("MCP 服务已关闭");
    }

    // Getter方法
    public AppConfig getAppConfig() { return appConfig; }
    public MCPConfig getMcpConfig() { return mcpConfig; }
    public AIService getAiService() { return aiService; }
    public SessionService getSessionService() { return sessionService; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }

    // 🔥 新增 contextManager Getter
    public ContextManager getContextManager() { return contextManager; }
    public ThinkingCodingUI getUi() { return ui; }
    public PerformanceMonitor getPerformanceMonitor() { return performanceMonitor; }

    // 🔥 新增 MCP 相关 Getter
    public MCPService getMcpService() { return mcpService; }
    public MCPToolManager getMcpToolManager() { return mcpToolManager; }
    public boolean isMCPEnabled() {
        return mcpConfig != null && mcpConfig.isEnabled();
    }
    public int getMCPToolCount() {
        return mcpService != null ? mcpService.getMCPTools().size() : 0;
    }

    // Builder模式
    public static class Builder {
        private AppConfig appConfig;
        private MCPConfig mcpConfig;
        private AIService aiService;
        private SessionService sessionService;
        private ToolRegistry toolRegistry;
        private ThinkingCodingUI ui;
        private PerformanceMonitor performanceMonitor;
        // 🔥 新增 MCP 字段
        private MCPService mcpService;
        private MCPToolManager mcpToolManager;
        // 🔥 新增上下文管理器字段
        private ContextManager contextManager;

        public Builder appConfig(AppConfig appConfig) {
            this.appConfig = appConfig;
            return this;
        }

        public Builder mcpConfig(MCPConfig mcpConfig) {
            this.mcpConfig = mcpConfig;
            return this;
        }

        public Builder aiService(AIService aiService) {
            this.aiService = aiService;
            return this;
        }

        public Builder sessionService(SessionService sessionService) {
            this.sessionService = sessionService;
            return this;
        }

        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        public Builder ui(ThinkingCodingUI ui) {
            this.ui = ui;
            return this;
        }

        public Builder performanceMonitor(PerformanceMonitor performanceMonitor) {
            this.performanceMonitor = performanceMonitor;
            return this;
        }

        // 🔥 新增 MCP Builder 方法
        public Builder mcpService(MCPService mcpService) {
            this.mcpService = mcpService;
            return this;
        }

        public Builder mcpToolManager(MCPToolManager mcpToolManager) {
            this.mcpToolManager = mcpToolManager;
            return this;
        }

        // 🔥 新增 contextManager Builder 方法
        public Builder contextManager(ContextManager contextManager) {
            this.contextManager = contextManager;
            return this;
        }

        public ThinkingCodingContext build() {
            return new ThinkingCodingContext(this);
        }
    }
}
