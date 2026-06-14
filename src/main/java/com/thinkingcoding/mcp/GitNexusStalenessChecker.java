package com.thinkingcoding.mcp;

import com.thinkingcoding.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * GitNexus 知识图谱过期检测与自动刷新服务。
 * 在调用 GitNexus MCP 工具之前，先检查索引是否过期，若过期则自动运行 analyze。
 */
public class GitNexusStalenessChecker {

    private static final Logger log = LoggerFactory.getLogger(GitNexusStalenessChecker.class);

    private final boolean enabled;
    private final long cacheTtlMs;
    private final long analyzeTimeoutMs;
    private final String statusCommand;
    private final String analyzeCommand;
    private final String workingDirectory;

    private volatile long lastCheckTime;
    private volatile boolean lastKnownFresh = true;
    private volatile String lastStalenessError;
    private volatile Runnable onIndexRefreshed;

    public GitNexusStalenessChecker(AppConfig.StalenessCheckConfig config, String workingDirectory) {
        this.enabled = config != null && config.isEnabled();
        this.cacheTtlMs = config != null ? config.getCacheTtlSeconds() * 1000L : 60_000L;
        this.analyzeTimeoutMs = config != null ? config.getAnalyzeTimeoutSeconds() * 1000L : 180_000L;
        this.statusCommand = config != null && config.getStatusCommand() != null
                ? config.getStatusCommand() : "npx gitnexus status";
        this.analyzeCommand = config != null && config.getAnalyzeCommand() != null
                ? config.getAnalyzeCommand() : "npx gitnexus analyze";
        this.workingDirectory = (workingDirectory != null && !workingDirectory.isBlank())
                ? workingDirectory : System.getProperty("user.dir");
        this.lastCheckTime = 0L;
    }

    public StalenessResult ensureFresh() {
        if (!enabled) {
            return StalenessResult.SKIPPED;
        }

        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastCheckTime < cacheTtlMs) {
                if (lastKnownFresh) {
                    return StalenessResult.FRESH;
                }
                return StalenessResult.failed(lastStalenessError != null
                        ? lastStalenessError
                        : "GitNexus index is stale. Run 'npx gitnexus analyze' manually.");
            }

            StalenessResult result = checkStaleness();
            lastCheckTime = System.currentTimeMillis();
            lastKnownFresh = result.canProceed();
            lastStalenessError = result.canProceed() ? null : result.getErrorMessage();
            return result;
        }
    }

    public void setOnIndexRefreshed(Runnable callback) {
        this.onIndexRefreshed = callback;
    }

    public void invalidateCache() {
        synchronized (this) {
            lastCheckTime = 0L;
        }
    }

    private StalenessResult checkStaleness() {
        String statusOutput = runCommand(statusCommand, 30_000L);
        if (statusOutput == null) {
            log.warn("GitNexus status command failed or timed out, proceeding without freshness check");
            return StalenessResult.FRESH;
        }

        boolean stale = parseStaleness(statusOutput);
        if (!stale) {
            return StalenessResult.FRESH;
        }

        log.info("GitNexus index is stale, running '{}'...", analyzeCommand);
        String analyzeOutput = runCommand(analyzeCommand, analyzeTimeoutMs);
        if (analyzeOutput == null) {
            String msg = "GitNexus index is stale and auto-refresh failed/timed out. "
                    + "Run 'npx gitnexus analyze' manually in terminal.";
            log.warn(msg);
            return StalenessResult.failed(msg);
        }

        log.info("GitNexus index refreshed successfully");
        if (onIndexRefreshed != null) {
            try {
                onIndexRefreshed.run();
            } catch (Exception e) {
                log.warn("onIndexRefreshed callback failed: {}", e.getMessage(), e);
            }
        }
        return StalenessResult.FRESH;
    }

    boolean parseStaleness(String output) {
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Status:") && trimmed.contains("stale")) {
                return true;
            }
        }

        String indexedCommit = null;
        String currentCommit = null;
        for (String line : output.split("\n")) {
            if (line.contains("Indexed commit:")) {
                indexedCommit = line.substring(line.lastIndexOf(':') + 1).trim();
            }
            if (line.contains("Current commit:")) {
                currentCommit = line.substring(line.lastIndexOf(':') + 1).trim();
            }
        }
        if (indexedCommit != null && currentCommit != null) {
            return !indexedCommit.equals(currentCommit);
        }

        log.warn("Unable to parse gitnexus status output, assuming fresh");
        return false;
    }

    String runCommand(String rawCommand, long timeoutMs) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            List<String> command;
            if (os.contains("win")) {
                command = List.of("cmd.exe", "/c", rawCommand);
            } else {
                command = List.of("sh", "-c", rawCommand);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(workingDirectory));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                log.warn("Command timed out after {}ms: {}", timeoutMs, rawCommand);
                return null;
            }

            if (process.exitValue() != 0) {
                log.warn("Command failed (exit={}): {}", process.exitValue(), rawCommand);
                return null;
            }

            return output.toString().trim();
        } catch (Exception e) {
            log.warn("Command execution error: {}", rawCommand, e);
            return null;
        }
    }

    public static final class StalenessResult {
        public static final StalenessResult FRESH = new StalenessResult(true, null);
        public static final StalenessResult SKIPPED = new StalenessResult(true, null);

        private final boolean proceed;
        private final String errorMessage;

        private StalenessResult(boolean proceed, String errorMessage) {
            this.proceed = proceed;
            this.errorMessage = errorMessage;
        }

        public boolean canProceed() {
            return proceed;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public static StalenessResult failed(String message) {
            return new StalenessResult(false, message);
        }
    }
}
