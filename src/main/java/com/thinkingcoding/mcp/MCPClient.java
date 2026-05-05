package com.thinkingcoding.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.mcp.model.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;

/**
 * MCP客户端，管理与单个MCP服务器的连接和通信
 */
public class MCPClient {
    private static final Logger log = LoggerFactory.getLogger(MCPClient.class); // 应该是 MCPClient.class
    private static final int MAX_ERROR_LINES = 20;
    private static final int GITHUB_CONNECT_TIMEOUT_SECONDS = 20;
    private static final int GITHUB_REQUEST_TIMEOUT_SECONDS = 30;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final Map<String, MCPTool> availableTools = new ConcurrentHashMap<>();
    private final Deque<String> recentErrorLines = new ArrayDeque<>();
    private String githubToken;
    private boolean initialized = false;
    private final String serverName;

    public MCPClient(String serverName) {
        this.serverName = serverName;
    }

    public boolean connect(String fullCommand, List<String> args) {
        try {
            validateGithubTokenArgs(args);
            githubToken = extractGithubToken(args);

            // 分割完整命令为命令和参数，并去除引号
            String[] parts = fullCommand.split("\\s+");
            String command = parts[0].replace("\"", "");  // 去除引号
            command = normalizeCommandForWindows(command);

            List<String> commandList = new ArrayList<>();
            commandList.add(command);

            // 添加命令的其他部分作为参数，并去除引号
            for (int i = 1; i < parts.length; i++) {
                String arg = parts[i].replace("\"", "");  // 去除引号
                commandList.add(arg);
            }

            // 添加额外的参数
            if (args != null && !args.isEmpty()) {
                for (String arg : args) {
                    commandList.add(arg.replace("\"", ""));  // 去除引号
                }
            }

            log.debug("完整命令: {}", String.join(" ", commandList));
            log.debug("工作目录: {}", System.getProperty("user.dir"));

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(new File(System.getProperty("user.dir")));
            configureProxyEnvironment(pb.environment());
            applyAmapApiKeyIfPresent(commandList, pb.environment());
            
            // 🔥 关键修复：不合并错误流，分开处理
            pb.redirectErrorStream(false);

            process = pb.start();

            // 🔥 启动错误流监控（只监控错误，不影响主输出）
            startErrorMonitoring();

            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            // 等待进程启动
            log.debug("⏳ 等待 MCP 服务器启动...");
            Thread.sleep(2000);

            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                String stderrSummary = getRecentErrorSummary();
                if (!stderrSummary.isEmpty()) {
                    log.error("❌ MCP服务器进程退出，退出码: {}\n\n📌 MCP 服务器最近 stderr：\n{}", exitCode, stderrSummary);
                } else {
                    log.error("❌ MCP服务器进程退出，退出码: {}", exitCode);
                }
                return false;
            }

            log.debug("✅ MCP 进程已启动，开始协议初始化...");

            if (initializeProtocol()) {
                listTools();
                initialized = true;
                log.debug("✅ MCP客户端初始化成功: {} ({} 个工具)", serverName, availableTools.size());
                return true;
            } else {
                log.error("❌ MCP 协议初始化失败");
            }

        } catch (Exception e) {
            log.error("❌ 连接MCP服务器失败: {}", serverName, e);
        }
        return false;
    }

    /**
     * 🔥 只监控错误流，避免和主输入流冲突
     */
    private void startErrorMonitoring() {
        Thread errorThread = new Thread(() -> {
            try (BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    appendRecentErrorLine(line);

                    // 过滤 npm 无关的错误
                    if (line.contains("npm ERR!") || line.contains("npm WARN") ||
                            line.contains("node_cache") || line.contains("_cacache") ||
                            line.contains("EPERM") || line.contains("operation not permitted")) {
                        continue;
                    }

                    // 仅在调试级别输出 MCP stderr，避免污染用户终端
                    log.debug("[MCP:{} stderr] {}", serverName, line);
                }
            } catch (Exception e) {
                // 正常结束
            }
        });
        errorThread.setDaemon(true);
        errorThread.setName("MCP-Error-" + serverName);
        errorThread.start();
    }

    private void startOutputMonitoring() {
        // 🔥 已废弃：不再使用此方法，避免和 reader 冲突
    }

    private boolean initializeProtocol() throws IOException {
        try {
            // 🔥 使用 MCPRequest 类来确保 JSON 序列化正确
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("protocolVersion", "2024-11-05");

            Map<String, Object> capabilities = new LinkedHashMap<>();
            Map<String, Object> roots = new LinkedHashMap<>();
            roots.put("listChanged", true);
            capabilities.put("roots", roots);
            capabilities.put("sampling", new LinkedHashMap<>());
            params.put("capabilities", capabilities);

            Map<String, Object> clientInfo = new LinkedHashMap<>();
            clientInfo.put("name", "ThinkingCoding");
            clientInfo.put("version", "1.0.0");
            params.put("clientInfo", clientInfo);

            log.debug("发送初始化请求...");

            // 使用 MCPRequest 确保正确序列化
            MCPRequest request = new MCPRequest("initialize", params);
            request.setId("1");  // 使用字符串 ID

            String json = objectMapper.writeValueAsString(request);
            log.debug("发送的JSON: {}", json);
            writer.write(json);
            writer.newLine();
            writer.flush();

            // 读取并记录所有输出，用于调试
            log.debug("等待MCP服务器响应...");
            MCPResponse response = readResponse(getInitTimeoutMillis());

            if (response != null) {
                log.debug("收到初始化响应: {}", response);
                if (response.getError() == null) {
                    log.debug("✅ MCP协议初始化成功");
                    return true;
                } else {
                    log.error("❌ MCP协议初始化错误: {}", response.getError().getMessage());
                }
            }

            return false;

        } catch (Exception e) {
            log.error("❌ 协议初始化异常: {}", e.getMessage(), e);
            return false;
        }
    }

    private void listTools() throws IOException {
        MCPRequest request = new MCPRequest("tools/list", Map.of());
        sendRequest(request);

        MCPResponse response = readResponse(getListToolsTimeoutMillis());
        if (response != null && response.getResult() != null) {
            Map<String, Object> result = (Map<String, Object>) response.getResult();
            List<Map<String, Object>> toolsList = (List<Map<String, Object>>) result.get("tools");

            if (toolsList != null) {
                for (Map<String, Object> toolData : toolsList) {
                    try {
                        MCPTool tool = objectMapper.convertValue(toolData, MCPTool.class);
                        availableTools.put(tool.getName(), tool);
                        log.debug("发现工具: {} - {}", tool.getName(), tool.getDescription());
                    } catch (Exception e) {
                        log.warn("解析工具失败: {}", toolData, e);
                    }
                }
            }
        }
    }

    private int getInitTimeoutMillis() {
        if (isGitNexusServer()) {
            return 60000;
        }
        return 15000;
    }

    private int getListToolsTimeoutMillis() {
        if (isGitNexusServer()) {
            return 20000;
        }
        return 3000;
    }

    private boolean isGitNexusServer() {
        return serverName != null && serverName.equalsIgnoreCase("gitnexus");
    }

    public Object callTool(String toolName, Map<String, Object> arguments) throws IOException {
        if (!initialized) {
            throw new IllegalStateException("MCP客户端未初始化");
        }

        if (!availableTools.containsKey(toolName)) {
            throw new IllegalArgumentException("工具不存在: " + toolName + ", 可用工具: " + availableTools.keySet());
        }

        MCPRequest request = new MCPRequest(
                "tools/call",
                Map.of("name", toolName, "arguments", arguments)
        );

        log.debug("🔧 调用MCP工具 [{}]: {} - 参数: {}", serverName, toolName, arguments);
        
        // 🔥 针对 GitHub 工具的额外验证和日志
        if (isGithubServer()) {
            if ("create_repository".equals(toolName)) {
                if (arguments == null || !arguments.containsKey("name")) {
                    throw new IllegalArgumentException("创建仓库需要提供 'name' 参数");
                }
                log.info("📦 准备创建 GitHub 仓库: {}", arguments.get("name"));
                log.debug("完整参数: {}", arguments);
            }
            
            // 检查是否有 Token 配置的提示
            log.info("💡 提示：如果持续失败，请检查 GitHub Token 是否有效且有足够权限");
        }
        
        sendRequest(request);
        MCPResponse response = readResponse(30000);

        if (response != null) {
            // 🔥 详细记录响应内容
            log.debug("📥 收到响应: id={}, error={}, result={}", 
                    response.getId(), response.getError(), response.getResult());
            
            if (response.getError() != null) {
                String errorMsg = String.format("MCP工具调用失败 [%s/%s]: %s (code: %s)",
                        serverName, toolName,
                        response.getError().getMessage(),
                        response.getError().getCode());
                
                // 🔥 针对 GitHub fetch failed 错误的详细诊断
                if (isGithubServer() && response.getError().getMessage().contains("fetch failed")) {
                    errorMsg += "\n\n🔍 GitHub API 调用失败，常见原因：";
                    errorMsg += "\n1. Token 无效或过期 - 请重新生成 Personal Access Token";
                    errorMsg += "\n2. Token 权限不足 - 需要 'repo' 和 'user' 权限";
                    errorMsg += "\n3. 网络问题 - 检查是否可以访问 https://api.github.com";
                    errorMsg += "\n4. 代理设置 - 如使用代理，请确认配置正确";
                    errorMsg += "\n\n📝 测试 Token 有效性：";
                    errorMsg += "\n   curl -H \"Authorization: token YOUR_TOKEN\" https://api.github.com/user";

                    String stderrSummary = getRecentErrorSummary();
                    if (!stderrSummary.isEmpty()) {
                        errorMsg += "\n\n📌 MCP 服务器最近 stderr：\n" + stderrSummary;
                    }
                }

                if (isGithubServer() && "create_repository".equals(toolName)
                        && isRetryableGithubFailure(response.getError().getMessage())) {
                    log.warn("GitHub MCP 创建仓库失败，尝试 REST 回退创建: {}", arguments != null ? arguments.get("name") : "unknown");
                    return createRepositoryViaRestFallback(arguments);
                }
                
                log.error("❌ {}", errorMsg);
                throw new IOException(errorMsg);
            }
            
            if (response.getResult() != null) {
                Map<String, Object> result = (Map<String, Object>) response.getResult();
                log.debug("✅ 工具调用成功: {}", result);
                return result.get("content");
            } else {
                log.warn("⚠️ 响应结果为空");
            }
        } else {
            log.error("❌ 未收到响应");
        }

        throw new IOException("工具调用无响应: " + serverName + "/" + toolName);
    }

    private void sendRequest(MCPRequest request) throws IOException {
        String json = objectMapper.writeValueAsString(request);
        writer.write(json);
        writer.newLine();
        writer.flush();
        log.debug("发送MCP请求[{}]: {}", serverName, json);
    }

    private MCPResponse readResponse(long timeoutMS) throws IOException {
        long startTime = System.currentTimeMillis();
        int attemptCount = 0;
    
        log.debug("⏳ 开始等待响应，超时时间: {}ms", timeoutMS);
    
        while (System.currentTimeMillis() - startTime < timeoutMS) {
            try {
                attemptCount++;
    
                // 检查进程状态
                if (process != null && !process.isAlive()) {
                    int exitCode = process.exitValue();
                    String stderrSummary = getRecentErrorSummary();
                    if (!stderrSummary.isEmpty()) {
                        log.error("❌ MCP进程已退出，退出码: {}\n\n📌 MCP 服务器最近 stderr：\n{}", exitCode, stderrSummary);
                    } else {
                        log.error("❌ MCP进程已退出，退出码: {}", exitCode);
                    }
                    throw new IOException("MCP进程异常退出，退出码: " + exitCode);
                }
    
                // 非阻塞检查是否有数据可读
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            log.debug("📨 收到数据 [尝试#{}]: {}", attemptCount, line);
    
                            // 尝试解析为 JSON
                            try {
                                MCPResponse response = objectMapper.readValue(line, MCPResponse.class);
                                log.debug("✅ 成功解析MCP响应 (耗时: {}ms)", System.currentTimeMillis() - startTime);
                                return response;
                            } catch (Exception e) {
                                log.warn("⚠️ 响应不是有效JSON，继续等待: {}. 错误: {}", line, e.getMessage());
                            }
                        }
                    }
                } else {
                    // 每500ms输出一次等待状态
                    if (attemptCount % 5 == 0) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.debug("⏳ 等待中... (已等待 {}ms / {}ms)", elapsed, timeoutMS);
                    }
                }
    
                Thread.sleep(100);
    
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("❌ 读取响应被中断");
                throw new IOException("读取响应被中断");
            }
        }
    
        // 超时
        long totalTime = System.currentTimeMillis() - startTime;
        log.error("❌ 读取响应超时！总等待时间: {}ms, 尝试次数: {}", totalTime, attemptCount);
    
        // 🔥 尝试最后一次读取并记录
        try {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    log.error("❌ 超时前收到的最后数据: {}", line);
                } else {
                    log.error("❌ 超时前没有可用数据");
                }
            } else {
                log.error("❌ 超时前 reader 未就绪");
            }
        } catch (Exception e) {
            log.error("❌ 超时后读取失败: {}", e.getMessage());
        }
    
        // 🔥 检查进程状态
        if (process != null) {
            if (!process.isAlive()) {
                log.error("❌ 进程已退出，退出码: {}", process.exitValue());
            } else {
                log.error("❌ 进程仍在运行，但未返回响应");
            }
        }
    
        throw new IOException("读取响应超时 (等待了 " + totalTime + "ms)");
    }

    public void disconnect() {
        try {
            if (writer != null) {
                MCPRequest request = new MCPRequest("shutdown", null);
                sendRequest(request);
                writer.close();
            }
            if (reader != null) {
                reader.close();
            }
            if (process != null) {
                process.destroy();
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            initialized = false;
            availableTools.clear();
            log.info("MCP客户端已断开: {}", serverName);
        } catch (Exception e) {
            log.error("断开MCP连接时出错: {}", serverName, e);
        }
    }

    public List<MCPTool> getAvailableTools() {
        return new ArrayList<>(availableTools.values());
    }

    public boolean isConnected() {
        return process != null && process.isAlive() && initialized;
    }

    public String getServerName() {
        return serverName;
    }

    private boolean isGithubServer() {
        return serverName != null && serverName.toLowerCase(Locale.ROOT).contains("github");
    }

    private void validateGithubTokenArgs(List<String> args) {
        if (!isGithubServer() || args == null || args.isEmpty()) {
            return;
        }

        int tokenFlagIndex = args.indexOf("--token");
        if (tokenFlagIndex < 0 || tokenFlagIndex + 1 >= args.size()) {
            throw new IllegalArgumentException("GitHub MCP 缺少 --token 参数，请在配置中提供有效 Token");
        }

        String token = args.get(tokenFlagIndex + 1);
        if (token == null) {
            throw new IllegalArgumentException("GitHub Token 为空，请检查配置");
        }

        String trimmed = token.trim();
        if (trimmed.isEmpty() || trimmed.contains("your_github_token_here") || trimmed.contains("YOUR_GITHUB")) {
            throw new IllegalArgumentException("GitHub Token 使用了占位符，请替换为真实 Personal Access Token");
        }
    }

    private String extractGithubToken(List<String> args) {
        if (!isGithubServer() || args == null) {
            return null;
        }

        int tokenFlagIndex = args.indexOf("--token");
        if (tokenFlagIndex < 0 || tokenFlagIndex + 1 >= args.size()) {
            return null;
        }

        String token = args.get(tokenFlagIndex + 1);
        return token == null ? null : token.trim();
    }

    private boolean isRetryableGithubFailure(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("fetch failed")
                || normalized.contains("timeout")
                || normalized.contains("socket")
                || normalized.contains("connect");
    }

    private Object createRepositoryViaRestFallback(Map<String, Object> arguments) throws IOException {
        if (githubToken == null || githubToken.isBlank()) {
            throw new IOException("GitHub REST 回退失败：未读取到有效 Token");
        }
        if (arguments == null || !arguments.containsKey("name")) {
            throw new IOException("GitHub REST 回退失败：缺少仓库名 name");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", arguments.get("name"));
        payload.put("private", toBoolean(arguments.get("private"), false));
        payload.put("auto_init", toBoolean(arguments.get("autoInit"), false));

        if (arguments.containsKey("description")) {
            payload.put("description", String.valueOf(arguments.get("description")));
        }
        if (arguments.containsKey("homepage")) {
            payload.put("homepage", String.valueOf(arguments.get("homepage")));
        }

        String endpoint = "https://api.github.com/user/repos";
        if (arguments.containsKey("org") && arguments.get("org") != null) {
            endpoint = "https://api.github.com/orgs/" + arguments.get("org") + "/repos";
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(GITHUB_REQUEST_TIMEOUT_SECONDS))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "token " + githubToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        try {
            HttpResponse<String> response = sendGithubRequest(buildGithubRestHttpClient(false), request);
            return parseGithubCreateRepositoryResponse(response);
        } catch (IOException e) {
            if (!containsPkixFailure(e)) {
                throw e;
            }

            log.warn("GitHub REST 回退遇到 TLS 证书链问题，尝试使用 Windows 证书存储重试...");
            if (isWindows()) {
                try {
                    HttpResponse<String> response = sendGithubRequest(buildGithubRestHttpClient(true), request);
                    return parseGithubCreateRepositoryResponse(response);
                } catch (IOException retryEx) {
                    throw new IOException(buildTlsFailureMessage(retryEx), retryEx);
                }
            }

            throw new IOException(buildTlsFailureMessage(e), e);
        }
    }

    private HttpResponse<String> sendGithubRequest(HttpClient httpClient, HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub REST 回退被中断", e);
        }
    }

    private Object parseGithubCreateRepositoryResponse(HttpResponse<String> response) throws IOException {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            Map<String, Object> repoInfo = objectMapper.readValue(response.body(), Map.class);
            String fullName = String.valueOf(repoInfo.get("full_name"));
            String htmlUrl = String.valueOf(repoInfo.get("html_url"));
            log.info("✅ GitHub REST 回退创建成功: {}", fullName);
            return "仓库创建成功: " + fullName + " (" + htmlUrl + ")";
        }

        String body = response.body();
        if (body != null && body.length() > 400) {
            body = body.substring(0, 400) + "...";
        }
        throw new IOException("GitHub REST 回退失败: HTTP " + status + ", 响应: " + body);
    }

    private HttpClient buildGithubRestHttpClient(boolean useWindowsRootTrustStore) throws IOException {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(GITHUB_CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL);

        ProxySelector proxySelector = buildGithubProxySelector();
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }

        SSLContext sslContext = buildGithubSslContext(useWindowsRootTrustStore);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }

        return builder.build();
    }

    private ProxySelector buildGithubProxySelector() {
        String proxy = firstNonBlank(
                System.getenv("https_proxy"),
                System.getenv("HTTPS_PROXY"),
                buildProxyFromSystemProperties("https"),
                System.getenv("http_proxy"),
                System.getenv("HTTP_PROXY"),
                buildProxyFromSystemProperties("http")
        );

        if (proxy == null) {
            return null;
        }

        try {
            URI proxyUri = toProxyUri(proxy);
            String host = proxyUri.getHost();
            int port = proxyUri.getPort();
            if (host == null || host.isBlank()) {
                log.warn("代理配置无效，已忽略: {}", proxy);
                return null;
            }

            if (port <= 0) {
                port = "https".equalsIgnoreCase(proxyUri.getScheme()) ? 443 : 80;
            }
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (Exception e) {
            log.warn("解析代理配置失败，已忽略: {}", proxy);
            return null;
        }
    }

    private URI toProxyUri(String proxy) {
        String normalized = proxy.trim();
        if (!normalized.contains("://")) {
            normalized = "http://" + normalized;
        }
        return URI.create(normalized);
    }

    private SSLContext buildGithubSslContext(boolean useWindowsRootTrustStore) throws IOException {
        try {
            KeyStore trustStore = resolveGithubTrustStore(useWindowsRootTrustStore);
            if (trustStore == null) {
                return null;
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            return sslContext;
        } catch (GeneralSecurityException e) {
            throw new IOException("初始化 GitHub HTTPS 信任配置失败", e);
        }
    }

    private KeyStore resolveGithubTrustStore(boolean useWindowsRootTrustStore) throws IOException, GeneralSecurityException {
        String trustStorePath = firstNonBlank(
                System.getProperty("thinkingcoding.github.trustStore"),
                System.getenv("THINKINGCODING_GITHUB_TRUSTSTORE"),
                System.getProperty("javax.net.ssl.trustStore")
        );

        if (trustStorePath != null) {
            String trustStoreType = firstNonBlank(
                    System.getProperty("thinkingcoding.github.trustStoreType"),
                    System.getenv("THINKINGCODING_GITHUB_TRUSTSTORE_TYPE"),
                    System.getProperty("javax.net.ssl.trustStoreType"),
                    "JKS"
            );
            String password = firstNonBlank(
                    System.getProperty("thinkingcoding.github.trustStorePassword"),
                    System.getenv("THINKINGCODING_GITHUB_TRUSTSTORE_PASSWORD"),
                    System.getProperty("javax.net.ssl.trustStorePassword")
            );

            KeyStore keyStore = KeyStore.getInstance(trustStoreType);
            try (InputStream inputStream = new FileInputStream(trustStorePath)) {
                keyStore.load(inputStream, password != null ? password.toCharArray() : null);
            }
            return keyStore;
        }

        if (useWindowsRootTrustStore && isWindows()) {
            KeyStore windowsRoot = KeyStore.getInstance("Windows-ROOT");
            windowsRoot.load(null, null);
            return windowsRoot;
        }

        return null;
    }

    private boolean containsPkixFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SSLHandshakeException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null) {
                String normalized = msg.toLowerCase(Locale.ROOT);
                if (normalized.contains("pkix")
                        || normalized.contains("unable to find valid certification path")
                        || normalized.contains("certificate_unknown")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildTlsFailureMessage(Throwable throwable) {
        String root = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
        return "GitHub HTTPS 证书校验失败: " + root +
                "\n\n请配置企业 CA 证书后重试：" +
                "\n1. 推荐设置 JVM 参数: -Djavax.net.ssl.trustStore=<路径> -Djavax.net.ssl.trustStorePassword=<密码>" +
                "\n2. 或设置专用参数: -Dthinkingcoding.github.trustStore=<路径>" +
                "\n3. Windows 环境可将企业 CA 导入系统\"受信任的根证书颁发机构\"";
    }

    private boolean isWindows() {
        String osName = System.getProperty("os.name");
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("windows");
    }

    private boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private void configureProxyEnvironment(Map<String, String> env) {
        String httpProxy = firstNonBlank(
                System.getenv("http_proxy"),
                System.getenv("HTTP_PROXY"),
                buildProxyFromSystemProperties("http")
        );
        String httpsProxy = firstNonBlank(
                System.getenv("https_proxy"),
                System.getenv("HTTPS_PROXY"),
                buildProxyFromSystemProperties("https"),
                httpProxy
        );
        String noProxy = firstNonBlank(
                System.getenv("no_proxy"),
                System.getenv("NO_PROXY"),
                System.getProperty("http.nonProxyHosts")
        );

        if (httpProxy != null) {
            env.put("GLOBAL_AGENT_HTTP_PROXY", httpProxy);
            env.put("HTTP_PROXY", httpProxy);
            env.put("http_proxy", httpProxy);
        }
        if (httpsProxy != null) {
            env.put("GLOBAL_AGENT_HTTPS_PROXY", httpsProxy);
            env.put("HTTPS_PROXY", httpsProxy);
            env.put("https_proxy", httpsProxy);
        }
        if (noProxy != null) {
            env.put("NO_PROXY", noProxy);
            env.put("no_proxy", noProxy);
        }
    }

    private String buildProxyFromSystemProperties(String protocol) {
        String host = System.getProperty(protocol + ".proxyHost");
        if (host == null || host.isBlank()) {
            return null;
        }

        String port = System.getProperty(protocol + ".proxyPort");
        if (port == null || port.isBlank()) {
            return protocol + "://" + host;
        }
        return protocol + "://" + host + ":" + port;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private void appendRecentErrorLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }

        synchronized (recentErrorLines) {
            recentErrorLines.addLast(line);
            while (recentErrorLines.size() > MAX_ERROR_LINES) {
                recentErrorLines.removeFirst();
            }
        }
    }

    private String getRecentErrorSummary() {
        synchronized (recentErrorLines) {
            if (recentErrorLines.isEmpty()) {
                return "";
            }
            return String.join("\n", recentErrorLines);
        }
    }

    private String normalizeCommandForWindows(String command) {
        if (command == null) {
            return null;
        }
        String normalized = command.trim();
        if (isWindows() && "npx".equalsIgnoreCase(normalized)) {
            return "npx.cmd";
        }
        return normalized;
    }

    private void applyAmapApiKeyIfPresent(List<String> commandList, Map<String, String> env) {
        if (commandList == null || env == null) {
            return;
        }
        if (env.containsKey("AMAP_MAPS_API_KEY")) {
            return;
        }

        int pkgIndex = commandList.indexOf("@amap/amap-maps-mcp-server");
        if (pkgIndex < 0) {
            return;
        }

        // The AMAP MCP server expects API key via environment variable.
        for (int i = pkgIndex + 1; i < commandList.size() - 1; i++) {
            String token = commandList.get(i);
            if ("--apiKey".equalsIgnoreCase(token) || "--apikey".equalsIgnoreCase(token)) {
                String value = commandList.get(i + 1);
                if (value != null && !value.startsWith("-")) {
                    env.put("AMAP_MAPS_API_KEY", value);
                    commandList.remove(i + 1);
                    commandList.remove(i);
                }
                return;
            }
        }

        String candidate = commandList.get(commandList.size() - 1);
        if (candidate != null && !candidate.startsWith("-")
                && !"@amap/amap-maps-mcp-server".equalsIgnoreCase(candidate)) {
            env.put("AMAP_MAPS_API_KEY", candidate);
            commandList.remove(commandList.size() - 1);
        }
    }
}
