package com.thinkingcoding.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ToolResult;
import com.thinkingcoding.service.AIService;
import com.thinkingcoding.tools.BaseTool;
import com.thinkingcoding.tools.ToolRegistry;

import java.util.HashMap;
import java.util.Map;

public class LazySkillToolAdapter extends BaseTool {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AppConfig.SkillConfig skillConfig;
    private final AppConfig appConfig;
    private final AIService aiService;
    private final ToolRegistry toolRegistry;
    private final Object inputSchema;
    private volatile Skill skill;

    public LazySkillToolAdapter(AppConfig.SkillConfig skillConfig, AppConfig appConfig, AIService aiService, ToolRegistry toolRegistry) {
        super(resolveName(skillConfig), resolveDescription(skillConfig));
        this.skillConfig = skillConfig;
        this.appConfig = appConfig;
        this.aiService = aiService;
        this.toolRegistry = toolRegistry;
        this.inputSchema = resolveSchema(skillConfig);
    }

    @Override
    public ToolResult execute(String input) {
        Skill loaded = getOrCreateSkill();
        if (loaded == null) {
            return ToolResult.error("Skill is not available", 0);
        }

        try {
            Map<String, Object> params = parseInput(input);
            String sourcePath = (String) params.get("source");
            String testPath = (String) params.get("test");

            if (sourcePath == null || sourcePath.isBlank()) {
                return ToolResult.error("Missing required parameter: source (path to source file)", 0);
            }

            int retries = resolveMaxRetries(skillConfig);
            String fullContext = SkillContextLoader.resolveFullContext(skillConfig);

            SkillExecutionContext context = new SkillExecutionContext(
                    sourcePath,
                    testPath != null && !testPath.isBlank() ? testPath : null,
                    null,
                    retries,
                    fullContext
            );

            SkillResult result = loaded.execute(context);
            if (result.isSuccess()) {
                StringBuilder output = new StringBuilder();
                output.append("✅ Skill executed successfully!\n");
                output.append(result.getMessage());
                if (result.getTestFilePath() != null) {
                    output.append("\n📄 Generated test file: ").append(result.getTestFilePath());
                }
                output.append("\n🔄 Attempts: ").append(result.getAttempts());
                return ToolResult.success(output.toString(), 0);
            }

            StringBuilder error = new StringBuilder();
            error.append("❌ Skill execution failed: ").append(result.getMessage());
            if (result.getTestFilePath() != null) {
                error.append("\n📄 Last test file: ").append(result.getTestFilePath());
            }
            if (result.getLastErrorLog() != null && !result.getLastErrorLog().isBlank()) {
                error.append("\n\n⚠️ Error details:\n").append(result.getLastErrorLog());
            }
            return ToolResult.error(error.toString(), 0);
        } catch (Exception e) {
            return ToolResult.error("Skill execution error: " + e.getMessage(), 0);
        }
    }

    @Override
    public String getCategory() {
        return "skill";
    }

    @Override
    public String getDescription() {
        Skill loaded = skill;
        if (loaded != null) {
            String desc = loaded.getDescription();
            if (desc != null && !desc.isBlank()) {
                return desc;
            }
        }
        return super.getDescription();
    }

    @Override
    public boolean isEnabled() {
        return skillConfig == null || skillConfig.isEnabled();
    }

    @Override
    public Object getInputSchema() {
        return inputSchema;
    }

    private Skill getOrCreateSkill() {
        if (skill != null) {
            return skill;
        }
        synchronized (this) {
            if (skill == null) {
                skill = SkillFactory.createSkillInstance(skillConfig, appConfig, aiService, toolRegistry);
            }
        }
        return skill;
    }

    private static int resolveMaxRetries(AppConfig.SkillConfig skillConfig) {
        if (skillConfig == null || skillConfig.getConfig() == null) {
            return 3;
        }
        Object value = skillConfig.getConfig().get("maxRetries");
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(String.valueOf(value).trim()));
            } catch (Exception ignored) {
                return 3;
            }
        }
        return 3;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String input) {
        if (input == null || input.isBlank()) {
            return new HashMap<>();
        }

        try {
            return OBJECT_MAPPER.readValue(input, Map.class);
        } catch (Exception e) {
            Map<String, Object> params = new HashMap<>();
            params.put("source", input.trim());
            return params;
        }
    }

    private static String resolveName(AppConfig.SkillConfig skillConfig) {
        if (skillConfig == null || skillConfig.getName() == null || skillConfig.getName().isBlank()) {
            return "skill";
        }
        return skillConfig.getName();
    }

    private static String resolveDescription(AppConfig.SkillConfig skillConfig) {
        String brief = SkillContextLoader.resolveBriefContext(skillConfig);
        if (brief != null && !brief.isBlank()) {
            return brief;
        }
        if (skillConfig != null && skillConfig.getDescription() != null) {
            return skillConfig.getDescription();
        }
        return skillConfig != null && skillConfig.getName() != null
                ? "Skill: " + skillConfig.getName()
                : "Skill tool";
    }

    private static Object resolveSchema(AppConfig.SkillConfig skillConfig) {
        if (skillConfig == null) {
            return null;
        }
        Map<String, Object> schema = skillConfig.getInputSchema();
        return (schema == null || schema.isEmpty()) ? null : schema;
    }
}

