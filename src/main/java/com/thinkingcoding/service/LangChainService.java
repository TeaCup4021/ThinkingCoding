package com.thinkingcoding.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.config.AppConfig;
import com.thinkingcoding.model.ChatMessage;
import com.thinkingcoding.model.ToolCall;
import com.thinkingcoding.rag.retrieval.RagContextEnricher;
import com.thinkingcoding.tools.BaseTool;
import com.thinkingcoding.tools.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 集成LangChain4j和DeepSeek API的AI服务实现。
 *
 * 完全使用 LangChain4j 原生 Tool Calling：
 * - 请求阶段注入 ToolSpecification
 * - 响应阶段读取 ToolExecutionRequest
 * - 不再依赖正则解析文本命令
 */
public class LangChainService implements AIService {
    private static final Logger log = LoggerFactory.getLogger(LangChainService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AppConfig appConfig;
    private final ToolRegistry toolRegistry;
    private final ContextManager contextManager;
    private final RagContextEnricher ragEnricher;
    private Consumer<ChatMessage> messageHandler;
    private Consumer<ToolCall> toolCallHandler;
    private StreamingChatModel streamingChatModel;

    private volatile boolean isGenerating = false;
    private volatile boolean shouldStop = false;

    public LangChainService(AppConfig appConfig, ToolRegistry toolRegistry,
                            ContextManager contextManager, RagContextEnricher ragEnricher) {
        this.appConfig = appConfig;
        this.toolRegistry = toolRegistry;
        this.contextManager = contextManager;
        this.ragEnricher = ragEnricher;
        initializeChatModel();
    }

    private void initializeChatModel() {
        try {
            String configuredDefaultModel = appConfig.getDefaultModel();
            AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(configuredDefaultModel);
            if (modelConfig == null) {
                if (appConfig.getModels() != null && !appConfig.getModels().isEmpty()) {
                    String fallbackModelKey = appConfig.getModels().keySet().iterator().next();
                    modelConfig = appConfig.getModelConfig(fallbackModelKey);
                    System.err.println("⚠️  defaultModel '" + configuredDefaultModel
                            + "' 未找到，已回退到: " + fallbackModelKey);
                } else {
                    System.err.println("❌ 错误：未找到默认模型配置 '" + configuredDefaultModel + "'");
                    System.err.println("   请检查 config.yaml 中的 models 配置和 defaultModel 设置");
                    return;
                }
            }
            
            // 验证必要配置
            if (modelConfig.getApiKey() == null || modelConfig.getApiKey().trim().isEmpty()) {
                System.err.println("❌ 错误：模型 '" + modelConfig.getName() + "' 的 API Key 未配置");
                System.err.println("   请在 config.yaml 中设置正确的 apiKey");
                return;
            }
            
            if (modelConfig.getBaseURL() == null || modelConfig.getBaseURL().trim().isEmpty()) {
                System.err.println("❌ 错误：模型 '" + modelConfig.getName() + "' 的 Base URL 未配置");
                return;
            }
            
            System.out.println("🔧 正在初始化模型: " + modelConfig.getName());
            System.out.println("   Base URL: " + modelConfig.getBaseURL());
            System.out.println("   API Key: " + (modelConfig.getApiKey().length() > 8 ? 
                modelConfig.getApiKey().substring(0, 8) + "..." : "***"));
            
            this.streamingChatModel = createDeepSeekModel(modelConfig);
            
            if (this.streamingChatModel != null) {
                System.out.println("✅ 模型初始化成功: " + modelConfig.getName());
            } else {
                System.err.println("❌ 模型初始化失败：createDeepSeekModel 返回 null");
            }
        } catch (Exception e) {
            System.err.println("❌ 初始化模型时发生异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private StreamingChatModel createDeepSeekModel(AppConfig.ModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseURL())
                .apiKey(config.getApiKey())
                .modelName(config.getName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Override
    public List<ChatMessage> chat(String input, List<ChatMessage> history, String modelName) {
        throw new UnsupportedOperationException("Use streamingChat for real AI service");
    }

    @Override
    public List<ChatMessage> streamingChat(String input, List<ChatMessage> history, String modelName) {
        // 前置校验：确保消息处理器和流式模型已初始化
        if (messageHandler == null) {
            throw new IllegalStateException("Message handler not set");
        }

        if (streamingChatModel == null) {
            throw new IllegalStateException("DeepSeek model not initialized. Please check your configuration.");
        }

        // 初始化生成状态标志
        isGenerating = true;
        shouldStop = false;

        // 记录用户输入到历史记录中
        final StringBuilder fullResponse = new StringBuilder();
        // 将用户输入添加到历史记录中，以便在生成过程中保持完整的对话上下文。
        final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        try {
            // 准备对话消息和工具规格定义
            List<dev.langchain4j.data.message.ChatMessage> messages = prepareMessages(input, history);
            List<ToolSpecification> toolSpecifications = buildToolSpecifications();

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolSpecifications)
                    .build();

            // 执行流式对话请求，注册响应处理器
            streamingChatModel.chat(request, new StreamingChatResponseHandler() {
                /**
                 * 处理AI响应的部分片段（Token级别）
                 */
                @Override
                public void onPartialResponse(String token) {
                    if (shouldStop) {
                        return;
                    }
                    fullResponse.append(token);
                    messageHandler.accept(new ChatMessage("assistant", token));
                }

                /**
                 * 处理AI响应完成事件，提取完整文本和工具调用
                 */
                @Override
                public void onCompleteResponse(ChatResponse chatResponse) {
                    try {
                        //  记录实际的 token 使用情况
                        if (chatResponse.tokenUsage() != null && contextManager != null) {
                            Object tokenUsage = chatResponse.tokenUsage();
                            int promptTokens = readTokenUsage(tokenUsage,
                                    "promptTokenCount", "inputTokenCount", "promptTokens", "inputTokens");
                            int completionTokens = readTokenUsage(tokenUsage,
                                    "completionTokenCount", "outputTokenCount", "completionTokens", "outputTokens");
                            int totalTokens = readTokenUsage(tokenUsage, "totalTokenCount", "totalTokens");
                            contextManager.recordTokenUsage(promptTokens, completionTokens, totalTokens);
                        }
                        
                        // 如果用户主动停止生成，添加截断标记
                        if (shouldStop && !fullResponse.isEmpty()) {
                            ChatMessage truncatedMessage = new ChatMessage("assistant",
                                    fullResponse + "\n\n💡 [生成已被用户停止]");
                            history.add(truncatedMessage);
                            return;
                        }

                        // 将完整的AI响应添加到历史记录
                        String assistantText = fullResponse.toString().trim();
                        if (!assistantText.isEmpty()) {
                            history.add(new ChatMessage("assistant", assistantText));
                        }

                        // 提取并处理工具调用请求
                        List<ToolCall> toolCalls = extractToolCalls(chatResponse);
                        if (toolCallHandler != null && !toolCalls.isEmpty()) {
                            // 当前 AgentLoop 只缓存一个待执行调用，这里按顺序触发并由上层取最后一个。
                            for (ToolCall toolCall : toolCalls) {
                                toolCallHandler.accept(toolCall);
                            }
                        }

                        System.out.println();
                    } finally {
                        // 重置生成状态并完成Future
                        isGenerating = false;
                        shouldStop = false;
                        completionFuture.complete(null);
                    }
                }

                /**
                 * 处理API调用错误，提供友好的错误提示
                 */
                @Override
                public void onError(Throwable error) {
                    try {
                        System.err.println("❌ DeepSeek API error: " + error.getMessage());
                        ChatMessage errorMessage = new ChatMessage("assistant",
                                "抱歉，我在处理您的请求时遇到了问题： " + error.getMessage());
                        messageHandler.accept(errorMessage);
                        history.add(errorMessage);
                    } finally {
                        // 重置生成状态并异常完成Future
                        isGenerating = false;
                        shouldStop = false;
                        completionFuture.completeExceptionally(error);
                    }
                }
            });

            // 等待流式响应完成，设置5分钟超时
            try {
                completionFuture.get(5, TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("⚠️  流式响应超时");
                completionFuture.cancel(true);
            } catch (Exception e) {
                System.err.println("⚠️  等待流式响应时发生错误: " + e.getMessage());
            }

        } catch (Exception e) {
            // 捕获服务层异常，重置状态并提供错误反馈
            isGenerating = false;
            shouldStop = false;

            System.err.println("❌ Service error: " + e.getMessage());
            ChatMessage errorMessage = new ChatMessage("assistant",
                    "服务暂时不可用，请稍后重试。错误信息: " + e.getMessage());
            messageHandler.accept(errorMessage);
            history.add(errorMessage);
        }

        return history;
    }

    @Override
    public String directChat(String systemPrompt, String userPrompt, String modelName) {
        if (streamingChatModel == null) {
            throw new IllegalStateException("Model not initialized. Please check your configuration.");
        }

        final StringBuilder fullResponse = new StringBuilder();
        final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        try {
            List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(dev.langchain4j.data.message.SystemMessage.from(systemPrompt));
            }
            messages.add(dev.langchain4j.data.message.UserMessage.from(userPrompt));

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(Collections.emptyList())
                    .build();

            streamingChatModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    fullResponse.append(token);
                }

                @Override
                public void onCompleteResponse(ChatResponse chatResponse) {
                    if (chatResponse.tokenUsage() != null && contextManager != null) {
                        try {
                            Object tokenUsage = chatResponse.tokenUsage();
                            int promptTokens = readTokenUsage(tokenUsage,
                                    "promptTokenCount", "inputTokenCount", "promptTokens", "inputTokens");
                            int completionTokens = readTokenUsage(tokenUsage,
                                    "completionTokenCount", "outputTokenCount", "completionTokens", "outputTokens");
                            int totalTokens = readTokenUsage(tokenUsage, "totalTokenCount", "totalTokens");
                            contextManager.recordTokenUsage(promptTokens, completionTokens, totalTokens);
                        } catch (Exception ignored) {
                        }
                    }
                    completionFuture.complete(null);
                }

                @Override
                public void onError(Throwable error) {
                    completionFuture.completeExceptionally(error);
                }
            });

            try {
                completionFuture.get(5, TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                completionFuture.cancel(true);
            } catch (Exception e) {
                if (!fullResponse.isEmpty()) {
                    return fullResponse.toString().trim();
                }
                throw new RuntimeException("Direct chat failed: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            System.err.println("Direct chat error: " + e.getMessage());
        }

        return fullResponse.toString().trim();
    }

    private List<ToolCall> extractToolCalls(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.aiMessage() == null || !chatResponse.aiMessage().hasToolExecutionRequests()) {
            return Collections.emptyList();
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolExecutionRequest request : chatResponse.aiMessage().toolExecutionRequests()) {
            Map<String, Object> arguments = parseToolArguments(request.arguments());
            // 严格原生 Tool Calling：保留模型返回的工具名，不做别名转换。
            toolCalls.add(new ToolCall(request.name(), arguments, null, false, 0, true));
        }
        return toolCalls;
    }

    private Map<String, Object> parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new HashMap<>();
        }

        try {
            return OBJECT_MAPPER.readValue(arguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("input", arguments);
            return fallback;
        }
    }

    /**
     * 构建工具规格列表，供模型在生成过程中调用工具时参考。
     * @return
     */
    private List<ToolSpecification> buildToolSpecifications() {
        List<ToolSpecification> specifications = new ArrayList<>();

        for (BaseTool tool : toolRegistry.getAllTools()) {
            ToolSpecification specification = ToolSpecification.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .parameters(resolveToolSchema(tool))
                    .build();
            specifications.add(specification);
        }

        return specifications;
    }

    /** 根据工具定义解析输入参数的 JSON Schema，支持用户自定义 schema 或内置默认 schema。
     * 解析失败时降级到默认 schema，确保工具调用的基本功能可用。
     * @param tool
     * @return
     */
    private JsonObjectSchema resolveToolSchema(BaseTool tool) {
        Object inputSchema = tool.getInputSchema();
        if (inputSchema != null) {
            try {
                Map<String, Object> schemaMap = OBJECT_MAPPER.convertValue(inputSchema, new TypeReference<Map<String, Object>>() {
                });
                return toJsonObjectSchema(schemaMap);
            } catch (Exception ignored) {
                // 降级到内置 schema
            }
        }
        return defaultToolSchema(tool.getName());
    }

    private JsonObjectSchema defaultToolSchema(String toolName) {
        if ("file_manager".equals(toolName)) {
            return JsonObjectSchema.builder()
                    .addEnumProperty("command", Arrays.asList("read", "write", "list", "create", "delete", "info"), "文件操作类型")
                    .addStringProperty("path", "文件或目录路径")
                    .addStringProperty("content", "当 command=write 时需要")
                    .required("command", "path")
                    .additionalProperties(false)
                    .build();
        }

        if ("command_executor".equals(toolName)) {
            return JsonObjectSchema.builder()
                    .addStringProperty("command", "要执行的命令")
                    .required("command")
                    .additionalProperties(false)
                    .build();
        }

        if ("grep_search".equals(toolName)) {
            return JsonObjectSchema.builder()
                    .addStringProperty("query", "检索关键词或正则表达式")
                    .addStringProperty("includePattern", "文件匹配规则，如 *.java")
                    .addBooleanProperty("isRegexp", "是否启用正则")
                    .required("query")
                    .additionalProperties(false)
                    .build();
        }

        return JsonObjectSchema.builder().additionalProperties(true).build();
    }

    /**
     * 将用户定义的工具输入参数 schema（通常是一个 Map）转换为 LangChain4j 的 JsonObjectSchema 对象。
     * @param schemaMap
     * @return
     */
    private JsonObjectSchema toJsonObjectSchema(Map<String, Object> schemaMap) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        Object description = schemaMap.get("description");
        if (description != null) {
            builder.description(String.valueOf(description));
        }

        // 属性
        Object properties = schemaMap.get("properties");
        if (properties instanceof Map<?, ?> propsMap) {
            for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                JsonSchemaElement element = toJsonSchemaElement(entry.getValue());
                builder.addProperty(name, element);
            }
        }

        Object required = schemaMap.get("required");
        if (required instanceof List<?> requiredList) {
            builder.required(requiredList.stream().map(String::valueOf).toArray(String[]::new));
        } else if (required instanceof String[] requiredArray) {
            builder.required(requiredArray);
        }
        //
        Object additionalProperties = schemaMap.get("additionalProperties");
        if (additionalProperties instanceof Boolean flag) {
            builder.additionalProperties(flag);
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private JsonSchemaElement toJsonSchemaElement(Object rawDefinition) {
        if (!(rawDefinition instanceof Map<?, ?> map)) {
            return JsonStringSchema.builder().build();
        }

        Object typeValue = map.containsKey("type") ? map.get("type") : "string";
        String type = String.valueOf(typeValue).toLowerCase(Locale.ROOT);
        String description = map.containsKey("description") ? String.valueOf(map.get("description")) : null;

        Object enumValues = map.get("enum");
        if (enumValues instanceof List<?> values && !values.isEmpty()) {
            JsonEnumSchema.Builder enumBuilder = JsonEnumSchema.builder()
                    .enumValues(values.stream().map(String::valueOf).toArray(String[]::new));
            if (description != null) {
                enumBuilder.description(description);
            }
            return enumBuilder.build();
        }

        return switch (type) {
            case "boolean" -> {
                JsonBooleanSchema.Builder booleanBuilder = JsonBooleanSchema.builder();
                if (description != null) {
                    booleanBuilder.description(description);
                }
                yield booleanBuilder.build();
            }
            case "integer" -> {
                JsonIntegerSchema.Builder integerBuilder = JsonIntegerSchema.builder();
                if (description != null) {
                    integerBuilder.description(description);
                }
                yield integerBuilder.build();
            }
            case "number" -> {
                JsonNumberSchema.Builder numberBuilder = JsonNumberSchema.builder();
                if (description != null) {
                    numberBuilder.description(description);
                }
                yield numberBuilder.build();
            }
            case "array" -> {
                JsonArraySchema.Builder arrayBuilder = JsonArraySchema.builder();
                if (description != null) {
                    arrayBuilder.description(description);
                }
                Object items = map.get("items");
                arrayBuilder.items(toJsonSchemaElement(items));
                yield arrayBuilder.build();
            }
            case "object" -> {
                Map<String, Object> nested = new HashMap<>((Map<String, Object>) map);
                yield toJsonObjectSchema(nested);
            }
            default -> {
                JsonStringSchema.Builder stringBuilder = JsonStringSchema.builder();
                if (description != null) {
                    stringBuilder.description(description);
                }
                yield stringBuilder.build();
            }
        };
    }

    private List<dev.langchain4j.data.message.ChatMessage> prepareMessages(String input, List<ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        // === Part 1: System Message — 角色定义、行为规范、Skills ===
        if (contextManager != null) {
            ChatMessage systemMsg = contextManager.buildProjectContextMessage();
            if (systemMsg != null) {
                messages.add(dev.langchain4j.data.message.SystemMessage.from(systemMsg.getContent()));
            }
        }

        // === Part 1.5: RAG Code Context — 从代码库检索到的相关代码 ===
        if (ragEnricher != null && ragEnricher.isEnabled()) {
            try {
                String ragContext = ragEnricher.enrich(input);
                if (ragContext != null && !ragContext.isBlank()) {
                    messages.add(dev.langchain4j.data.message.SystemMessage.from(ragContext));
                }
            } catch (Exception e) {
                log.warn("RAG enrichment failed silently: {}", e.getMessage());
            }
        }

        // === Part 2: History Messages — User/Assistant/System 角色，经压缩管理 ===
        if (contextManager != null && history != null && !history.isEmpty()) {
            List<ChatMessage> managedHistory = contextManager.getContextForAI(history);
            if (managedHistory != null && !managedHistory.isEmpty()) {
                messages.addAll(convertToLangChainHistory(managedHistory));
            }
        }

        // === Part 3: Current Context — 拼接到最新 UserMessage ===
        String augmentedInput = input;
        if (contextManager != null) {
            String currentContext = contextManager.buildCurrentContext(toolRegistry.getAllTools());
            if (currentContext != null && !currentContext.isBlank()) {
                augmentedInput = input + currentContext;
            }
        }
        messages.add(dev.langchain4j.data.message.UserMessage.from(augmentedInput));

        return messages;
    }

    private List<dev.langchain4j.data.message.ChatMessage> convertToLangChainHistory(List<ChatMessage> history) {
        return history.stream()
                .map(msg -> {
                    if ("user".equals(msg.getRole())) {
                        return dev.langchain4j.data.message.UserMessage.from(msg.getContent());
                    } else if ("assistant".equals(msg.getRole())) {
                        return dev.langchain4j.data.message.AiMessage.from(msg.getContent());
                    } else {
                        return dev.langchain4j.data.message.SystemMessage.from(msg.getContent());
                    }
                })
                .collect(Collectors.toList());
    }

    @Override
    public void setMessageHandler(Consumer<ChatMessage> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void setToolCallHandler(Consumer<ToolCall> handler) {
        this.toolCallHandler = handler;
    }

    @Override
    public boolean validateModel(String modelName) {
        return appConfig.getModels().containsKey(modelName);
    }

    @Override
    public List<String> getAvailableModels() {
        return new ArrayList<>(appConfig.getModels().keySet());
    }


    public boolean isGenerating() {
        return isGenerating;
    }

    public void stopCurrentGeneration() {
        if (isGenerating) {
            shouldStop = true;
            System.out.println("⏸️  正在停止生成...");
        }
    }

    private int readTokenUsage(Object tokenUsage, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                java.lang.reflect.Method method = tokenUsage.getClass().getMethod(methodName);
                Object value = method.invoke(tokenUsage);
                if (value instanceof Number number) {
                    return number.intValue();
                }
            } catch (NoSuchMethodException ignored) {
                // Try next method name
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }
}
