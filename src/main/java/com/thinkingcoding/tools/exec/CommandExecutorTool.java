package com.thinkingcoding.tools.exec;

import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.tools.BaseTool;
import com.thinkingcoding.model.ToolResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 命令执行工具，允许执行预定义的系统命令
 */
public class CommandExecutorTool extends BaseTool {
    private final Set<String> allowedCommands;
    private final AppConfig appConfig;

    public CommandExecutorTool(AppConfig appConfig) {
        super("command_executor",
              "Execute whitelisted system commands (git, mvn, npm, ls, curl, python, node, etc.) via the OS shell. " +
              "Only pre-approved commands are allowed — unknown commands are rejected. " +
              "Use for: running builds (mvn, gradle), version control (git), package managers (npm), file utils (ls, cat, find). " +
              "NOT for: executing code snippets directly (use code_executor for Java/Python/JS), " +
              "reading/writing project files (use file_manager), or searching code (use grep_search / semantic_search).");
        this.appConfig = appConfig;

        if (appConfig.getTools().getCommandExec().getAllowedCommands() != null) {
            this.allowedCommands = new HashSet<>(Arrays.asList(appConfig.getTools().getCommandExec().getAllowedCommands()));
        } else {
            // 扩展的默认允许命令 - 支持更多开发和系统命令
            this.allowedCommands = Set.of(
                // 基础系统命令
                "ls", "pwd", "cat", "grep", "find", "echo", "which", "where", "whoami", "date", "uname",
                // 文件操作
                "head", "tail", "wc", "sort", "uniq", "diff", "cp", "mv", "mkdir", "rmdir", "rm", "chmod", "chown",
                // 开发工具
                "java", "javac", "python", "python3", "node", "npm", "mvn", "gradle", "gcc", "g++", "make", "cmake",
                // Git命令
                "git", "git-status", "git-log", "git-add", "git-commit", "git-push", "git-pull", "git-branch", "git-checkout", "git-clone", "git-diff",
                // 网络工具
                "ping", "curl", "wget", "ssh", "scp", "rsync", "telnet", "netstat", "lsof",
                // 系统管理
                "ps", "top", "htop", "kill", "killall", "df", "du", "free", "uptime", "tar", "gzip", "zip", "unzip",
                // 其他工具
                "man", "help", "history", "clear", "reset", "exit", "tree", "file", "stat", "ln"
            );
        }
    }

    /**
     * 执行系统命令并返回执行结果
     * <p>
     * 该方法支持两种输入格式：
     * 1. 纯文本命令：直接执行命令字符串
     * 2. JSON格式：{"command":"命令内容"}，从JSON中提取command字段
     * <p>
     * 执行流程：
     * - 验证输入非空
     * - 解析命令（支持JSON格式）
     * - 安全检查：验证命令是否在允许列表中
     * - 根据操作系统选择对应的shell执行（Windows使用cmd.exe，其他使用sh）
     * - 捕获标准输出和错误流
     * - 等待进程完成并返回结果
     *
     * @param input 要执行的命令字符串，可以是纯文本或JSON格式
     * @return ToolResult 包含执行结果的对象，成功时返回输出内容，失败时返回错误信息
     */
    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();

        try {
            //trim() 以确保输入不只是空格(去除字符串两端的空白字符)
            if (input == null || input.trim().isEmpty()) {
                return error("No command provided", System.currentTimeMillis() - startTime);
            }

            //  处理 JSON 格式的输入：{"command":"rm sessions/*"}
            String command = input;
            if (input.trim().startsWith("{")) {
                try {
                    // 使用简单的正则提取 command 字段的值
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"command\"\\s*:\\s*\"([^\"]+)\"");
                    java.util.regex.Matcher matcher = pattern.matcher(input);
                    if (matcher.find()) {
                        command = matcher.group(1);
                    }
                } catch (Exception e) {
                    // 如果解析失败，使用原始输入
                    command = input;
                }
            }

            String[] commandParts = command.split("\\s+");
            String baseCommand = commandParts[0].toLowerCase();

            // 安全检查
            if (!isCommandAllowed(baseCommand)) {
                return error("Command not allowed: " + baseCommand + ". Allowed commands: " + allowedCommands,
                        System.currentTimeMillis() - startTime);
            }

            //  执行命令 - 通过 shell 执行以支持通配符等特性
            ProcessBuilder processBuilder;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                // Windows 系统
                processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                // Unix/Linux/Mac 系统
                processBuilder = new ProcessBuilder("sh", "-c", command);
            }

            //  设置工作目录为当前目录（作为默认路径）
            // 但命令中可以使用绝对路径访问其他目录
            processBuilder.directory(new java.io.File(System.getProperty("user.dir")));
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // 等待进程完成
            int exitCode = process.waitFor();

            String result = output.toString().trim();
            if (exitCode != 0) {
                return error("Command failed with exit code " + exitCode + ":\n" + result,
                        System.currentTimeMillis() - startTime);
            }

            return success(result.isEmpty() ? "Command executed successfully (no output)" : result,
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return error("Command execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private boolean isCommandAllowed(String command) {
        return allowedCommands.contains(command.toLowerCase());
    }

    public Set<String> getAllowedCommands() {
        return new HashSet<>(allowedCommands);
    }

    @Override
    public String getCategory() {
        return "exec";
    }

    @Override
    public boolean isEnabled() {
        return appConfig != null && appConfig.getTools().getCommandExec().isEnabled();
    }
}