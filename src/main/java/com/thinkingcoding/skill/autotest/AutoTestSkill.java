package com.thinkingcoding.skill.autotest;

import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.service.AIService;
import com.thinkingcoding.skill.Skill;
import com.thinkingcoding.skill.SkillExecutionContext;
import com.thinkingcoding.skill.SkillResult;
import com.thinkingcoding.tools.BaseTool;
import com.thinkingcoding.tools.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto test generation and repair loop.
 */
public class AutoTestSkill implements Skill {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(?:java)?\\s*([\\s\\S]*?)```");
    private static final int DEFAULT_ERROR_SNIPPET_CHARS = 3200;
    private static final int DEFAULT_LOG_TAIL_CHARS = 1800;
    private static final int DEFAULT_MAX_ERROR_CONTEXT_CHARS = 8000;
    private static final String[] IMPORTANT_MARKERS = new String[]{
            "caused by", "exception", "error", "failed", "compilation", "cannot find symbol", "assert"
    };

    private final AIService aiService;
    private final ToolRegistry toolRegistry;
    private final AppConfig appConfig;

    public AutoTestSkill(AIService aiService, ToolRegistry toolRegistry, AppConfig appConfig) {
        this.aiService = aiService;
        this.toolRegistry = toolRegistry;
        this.appConfig = appConfig;
    }

    @Override
    public String getName() {
        return "autotest";
    }

    @Override
    public String getDescription() {
        return "自动生成单元测试代码，并在测试失败时自动修复。适用于 Java、Python、Go、JavaScript/TypeScript 项目。当用户要求为某个源代码文件创建测试时调用此 skill。需要提供源文件路径，可选提供测试文件路径。";
    }

    @Override
    public Object getInputSchema() {
        Map<String, Object> schema = new java.util.HashMap<>();
        schema.put("type", "object");
        schema.put("description", "Auto test generation skill input parameters");
        
        Map<String, Object> properties = new java.util.HashMap<>();
        
        // source parameter
        Map<String, Object> sourceParam = new java.util.HashMap<>();
        sourceParam.put("type", "string");
        sourceParam.put("description", "Path to the source code file that needs tests");
        properties.put("source", sourceParam);
        
        // test parameter (optional)
        Map<String, Object> testParam = new java.util.HashMap<>();
        testParam.put("type", "string");
        testParam.put("description", "Optional path for the generated test file. If not provided, will be auto-derived from source path");
        properties.put("test", testParam);
        
        schema.put("properties", properties);
        schema.put("required", new String[]{"source"});
        
        return schema;
    }

    @Override
    public SkillResult execute(SkillExecutionContext context) {
        Path sourcePath = Paths.get(context.getSourcePath()).toAbsolutePath().normalize();
        if (!Files.exists(sourcePath)) {
            return SkillResult.failure("Source file not found: " + sourcePath, null, null, 0);
        }

        try {
            String sourceCode = Files.readString(sourcePath);
            String testPathString = context.getTestPath();
            if (testPathString == null || testPathString.isBlank()) {
                testPathString = deriveTestPath(sourcePath, sourceCode).toString();
            }

            Path testPath = Paths.get(testPathString).toAbsolutePath().normalize();
            Files.createDirectories(testPath.getParent());

            String model = (context.getModel() == null || context.getModel().isBlank())
                    ? appConfig.getDefaultModel() : context.getModel();

            String skillContext = context.getSkillContext();
            String generatedTest = generateInitialTest(sourcePath.toString(), sourceCode, testPath.toString(), model, skillContext);
            Files.writeString(testPath, generatedTest);

            int maxRetries = Math.max(1, context.getMaxRetries());
            int maxMeaningfulFixes = Math.max(1, maxRetries);
            int meaningfulFixes = 0;
            String lastError = null;
            AutoTestOptions options = resolveOptions();

            int attempt = 0;
            while (attempt < maxRetries) {
                attempt++;
                ToolResult runResult = runTargetTest(testPath);
                if (runResult.isSuccess()) {
                    String currentTestCode = Files.readString(testPath);
                    if (isMeaningfulTest(sourcePath.toString(), currentTestCode)) {
                        return SkillResult.success("AutoTest skill finished successfully.", testPath.toString(), attempt);
                    }

                    lastError = buildMeaningfulFailure(sourcePath.toString(), sourceCode, currentTestCode);
                    if (meaningfulFixes >= maxMeaningfulFixes) {
                        break;
                    }
                    meaningfulFixes++;
                    String fixedTest = fixTest(sourcePath.toString(), sourceCode, testPath.toString(), currentTestCode, lastError, model, skillContext);
                    if (fixedTest.equals(currentTestCode)) {
                        continue;
                    }
                    Files.writeString(testPath, fixedTest);
                    continue;
                }

                lastError = shrinkLog(runResult.getError() != null ? runResult.getError() : runResult.getOutput(), options);
                if (attempt >= maxRetries) {
                    break;
                }

                String currentTestCode = Files.readString(testPath);
                String fixedTest = fixTest(sourcePath.toString(), sourceCode, testPath.toString(), currentTestCode, lastError, model, skillContext);
                if (fixedTest.equals(currentTestCode)) {
                    continue;
                }
                Files.writeString(testPath, fixedTest);
            }

            int totalAttempts = Math.max(1, attempt + meaningfulFixes);
            return SkillResult.failure("AutoTest skill failed after retries.", testPath.toString(), lastError, totalAttempts);
        } catch (Exception e) {
            return SkillResult.failure("AutoTest skill failed: " + e.getMessage(), null, null, 0);
        }
    }

    private String generateInitialTest(String sourcePath, String sourceCode, String testPath, String model, String skillContext) {
        String prompt = TestPromptBuilder.buildInitialPrompt(sourcePath, sourceCode, testPath);
        String raw = askModel(prompt, model, skillContext);
        if (raw == null || raw.isBlank()) {
            raw = askModel(prompt, model, skillContext);
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("AI returned empty response after 2 attempts");
        }
        return extractCodeOrThrow(raw);
    }

    private String fixTest(String sourcePath, String sourceCode, String testPath, String currentTestCode, String errorLog, String model, String skillContext) {
        String prompt = TestPromptBuilder.buildFixPrompt(sourcePath, sourceCode, testPath, currentTestCode, errorLog);
        String raw = askModel(prompt, model, skillContext);
        if (raw == null || raw.isBlank()) {
            return currentTestCode;
        }
        try {
            return extractCodeOrThrow(raw);
        } catch (Exception e) {
            return currentTestCode;
        }
    }

    private String askModel(String prompt, String model, String skillContext) {
        String systemPrompt = (skillContext != null && !skillContext.isBlank())
                ? skillContext
                : "You are a professional test code generator. Output ONLY the test code in a single markdown code block. No explanations.";
        return aiService.directChat(systemPrompt, prompt, model);
    }

    private ToolResult runTargetTest(Path testPath) {
        BaseTool commandExecutor = toolRegistry.getTool("command_executor");
        if (commandExecutor == null) {
            return ToolResult.error("command_executor tool is not available", 0);
        }
        
        String fileName = testPath.getFileName().toString();
        String command = "";
        if (fileName.endsWith(".java")) {
            String testClassName = fileName.replace(".java", "");
            command = "mvn -Dtest=" + testClassName + " test";
            // Check if maven project exists or if gradle wrapper is there, but default to maven for now
        } else if (fileName.endsWith(".py")) {
            command = "pytest " + testPath.toString();
        } else if (fileName.endsWith(".go")) {
            command = "go test " + testPath.getParent().toString();
        } else if (fileName.endsWith(".js") || fileName.endsWith(".ts")) {
            command = "npm test -- " + testPath.toString();
        } else {
            return ToolResult.error("Unsupported test language for automatic test execution.", 1);
        }
        
        return commandExecutor.execute(command);
    }

    private boolean isMeaningfulTest(String sourcePath, String testCode) {
        if (testCode == null || testCode.isBlank()) {
            return false;
        }
        String normalized = testCode.replace("\r\n", "\n").toLowerCase(Locale.ROOT);
        if (normalized.contains("asserttrue(true)") || normalized.contains("asserttrue( true )")) {
            return false;
        }
        if (normalized.contains("asserttrue(false)") || normalized.contains("assertfalse(true)")) {
            return false;
        }
        if (normalized.contains("todo") || normalized.contains("not implemented")) {
            return false;
        }
        if (sourcePath != null && sourcePath.endsWith(".java")) {
            return normalized.contains("assert") || normalized.contains("verify(") || normalized.contains("assertthat(");
        }
        return normalized.contains("assert") || normalized.contains("expect(") || normalized.contains("should");
    }

    private String buildMeaningfulFailure(String sourcePath, String sourceCode, String testCode) {
        StringBuilder message = new StringBuilder();
        message.append("生成的测试未验证真实行为（存在无意义断言或缺少有效验证），请改写为能体现被测试类正确性的测试。\n");

        List<String> methods = extractPublicMethods(sourceCode);
        if (!methods.isEmpty()) {
            message.append("建议至少覆盖的公开方法: ").append(String.join(", ", methods)).append("\n");
        }

        String className = extractPrimaryClassName(sourceCode);
        if (className != null) {
            message.append("被测类: ").append(className).append("\n");
        }

        if (testCode != null && !testCode.isBlank()) {
            message.append("当前测试片段可能存在占位断言，请替换为真实断言。\n");
        }
        return message.toString();
    }

    private List<String> extractPublicMethods(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return new ArrayList<>();
        }
        Pattern methodPattern = Pattern.compile("public\\s+(?:static\\s+)?[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\(");
        Matcher matcher = methodPattern.matcher(sourceCode);
        List<String> methods = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name == null || name.isBlank() || "main".equals(name)) {
                continue;
            }
            if (!methods.contains(name)) {
                methods.add(name);
            }
        }
        return methods;
    }

    private String extractPrimaryClassName(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return null;
        }
        Matcher matcher = CLASS_PATTERN.matcher(sourceCode);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Path deriveTestPath(Path sourcePath, String sourceCode) {
        String fileName = sourcePath.getFileName().toString();
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileName.substring(dotIndex);
            fileName = fileName.substring(0, dotIndex);
        }

        if (".java".equals(extension)) {
            String packageName = extractPackageName(sourceCode);
            String className = extractClassName(sourceCode);
            if (className == null || className.isBlank()) {
                className = fileName;
            }

            String relative = packageName.isBlank()
                    ? className + "AutoTest.java"
                    : packageName.replace('.', '/') + "/" + className + "AutoTest.java";

            Path projectRoot = Paths.get(System.getProperty("user.dir"));
            return projectRoot.resolve("src/test/java").resolve(relative);
        } else if (".py".equals(extension)) {
            return Paths.get(System.getProperty("user.dir")).resolve("tests").resolve("test_" + fileName + ".py");
        } else if (".go".equals(extension)) {
            return sourcePath.getParent().resolve(fileName + "_test.go");
        } else if (".js".equals(extension) || ".ts".equals(extension)) {
            return sourcePath.getParent().resolve(fileName + ".test" + extension);
        } else {
            return sourcePath.getParent().resolve(fileName + "Test" + extension);
        }
    }

    private String extractPackageName(String sourceCode) {
        Matcher matcher = PACKAGE_PATTERN.matcher(sourceCode);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractClassName(String sourceCode) {
        Matcher matcher = CLASS_PATTERN.matcher(sourceCode);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractCodeOrThrow(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            throw new IllegalStateException("AI returned empty response, cannot extract code");
        }

        Matcher javaMatcher = CODE_BLOCK_PATTERN.matcher(modelOutput);
        if (javaMatcher.find()) {
            return javaMatcher.group(1).trim() + System.lineSeparator();
        }

        Matcher anyCodeMatcher = Pattern.compile("```\\w*\\s*([\\s\\S]*?)```").matcher(modelOutput);
        if (anyCodeMatcher.find()) {
            return anyCodeMatcher.group(1).trim() + System.lineSeparator();
        }

        if (modelOutput.contains("class ") || modelOutput.contains("def ") || modelOutput.contains("func ")) {
            return modelOutput.trim() + System.lineSeparator();
        }

        String cleaned = modelOutput
                .replaceAll("(?m)^\\s*(?:#|//|/\\*|\\*|>|\\-\\s).*$", "")
                .replaceAll("(?m)^[A-Z].*[:：].*$", "")
                .trim();
        if (cleaned.contains("class ") || cleaned.contains("def ") || cleaned.contains("func ")) {
            return cleaned + System.lineSeparator();
        }

        throw new IllegalStateException("Failed to extract code from AI response. Response summary: "
                + modelOutput.substring(0, Math.min(300, modelOutput.length())));
    }

    private String shrinkLog(String log, AutoTestOptions options) {
        String normalized = normalizeLog(log);
        if (normalized.isEmpty()) {
            return "";
        }

        String summary = extractImportantLines(normalized, options.errorSnippetChars);
        String tail = tail(normalized, options.logTailChars);

        String context;
        if (summary.isBlank()) {
            context = tail;
        } else {
            context = "[error_summary]\n" + summary + "\n\n[recent_log_tail]\n" + tail;
        }

        if (context.length() <= options.maxErrorContextChars) {
            return context;
        }
        return context.substring(context.length() - options.maxErrorContextChars);
    }

    private String normalizeLog(String log) {
        if (log == null || log.isBlank()) {
            return "";
        }
        return log.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String extractImportantLines(String normalizedLog, int maxChars) {
        String[] lines = normalizedLog.split("\\n");
        Set<String> selected = new LinkedHashSet<>();

        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (containsImportantMarker(trimmed) || isStackTraceLine(trimmed)) {
                selected.add(trimmed);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String line : selected) {
            if (sb.length() + line.length() + 1 > maxChars) {
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private boolean containsImportantMarker(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String marker : IMPORTANT_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStackTraceLine(String line) {
        return line.startsWith("at ") || line.startsWith("... ");
    }

    private String tail(String value, int chars) {
        if (value.length() <= chars) {
            return value;
        }
        return value.substring(value.length() - chars);
    }

    private AutoTestOptions resolveOptions() {
        AutoTestOptions defaults = new AutoTestOptions(
                DEFAULT_ERROR_SNIPPET_CHARS,
                DEFAULT_LOG_TAIL_CHARS,
                DEFAULT_MAX_ERROR_CONTEXT_CHARS
        );

        if (appConfig == null || appConfig.getSkills() == null) {
            return defaults;
        }

        for (AppConfig.SkillConfig skillConfig : appConfig.getSkills()) {
            if (skillConfig == null || skillConfig.getName() == null || !"autotest".equalsIgnoreCase(skillConfig.getName())) {
                continue;
            }
            Map<String, Object> config = skillConfig.getConfig();
            if (config == null || config.isEmpty()) {
                return defaults;
            }

            return new AutoTestOptions(
                    intConfig(config.get("errorSnippetChars"), defaults.errorSnippetChars),
                    intConfig(config.get("logTailChars"), defaults.logTailChars),
                    intConfig(config.get("maxErrorContextChars"), defaults.maxErrorContextChars)
            );
        }
        return defaults;
    }

    private int intConfig(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return Math.max(256, number.intValue());
        }
        try {
            return Math.max(256, Integer.parseInt(String.valueOf(value).trim()));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static class AutoTestOptions {
        private final int errorSnippetChars;
        private final int logTailChars;
        private final int maxErrorContextChars;

        private AutoTestOptions(int errorSnippetChars, int logTailChars, int maxErrorContextChars) {
            this.errorSnippetChars = errorSnippetChars;
            this.logTailChars = logTailChars;
            this.maxErrorContextChars = maxErrorContextChars;
        }
    }
}



