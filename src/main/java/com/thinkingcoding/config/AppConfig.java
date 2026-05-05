package com.thinkingcoding.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用配置类，包含模型和工具的配置
 * 具体包括：ToolsConfig, ToolConfig, ModelConfig
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class AppConfig {

    @JsonProperty("models")
    private Map<String, ModelConfig> models;

    @JsonProperty("defaultModel")
    private String defaultModel;

    @JsonProperty("tools")
    private ToolsConfig tools = new ToolsConfig(); // Ensure tools is initialized

    @JsonProperty("ai")
    private AIConfig ai = new AIConfig(); // AI行为配置

    @JsonProperty("skills")
    private List<SkillConfig> skills = new ArrayList<>();

    @JsonProperty("rag")
    private RagConfig rag = new RagConfig();

    public List<SkillConfig> getSkills() {
        return skills;
    }

    public void setSkills(List<SkillConfig> skills) {
        this.skills = skills;
    }

    public RagConfig getRag() {
        if (rag == null) {
            rag = new RagConfig();
        }
        return rag;
    }

    public void setRag(RagConfig rag) {
        this.rag = rag;
    }

    // Getters and Setters
    public Map<String, ModelConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelConfig> models) {
        this.models = models;
    }

    public ModelConfig getModelConfig(String modelName) {
        return models != null ? models.get(modelName) : null;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public ToolsConfig getTools() {
        if (tools == null) {
            tools = new ToolsConfig(); // Ensure tools is not null
        }
        return tools;
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }

    public AIConfig getAi() {
        if (ai == null) {
            ai = new AIConfig();
        }
        return ai;
    }

    public void setAi(AIConfig ai) {
        this.ai = ai;
    }


    public String getDefaultModel() {
        // 如果配置了defaultModel，使用配置的值
        if (defaultModel != null && !defaultModel.trim().isEmpty()) {
            return defaultModel;
        }

        // 如果没有配置defaultModel，使用第一个模型作为默认
        if (models != null && !models.isEmpty()) {
            return models.keySet().iterator().next();
        }

        // 如果连模型都没有配置，返回null或抛出异常
        return null;
    }


    @Data
    public static class ModelConfig {
        @JsonProperty("name")
        private String name;

        @JsonProperty("baseURL")
        private String baseURL;

        @JsonProperty("apiKey")
        private String apiKey;

        @JsonProperty("streaming")
        private boolean streaming = true;

        @JsonProperty("maxTokens")
        private Integer maxTokens = 4096;

        @JsonProperty("maxContextTokens")
        private Integer maxContextTokens;

        @JsonProperty("temperature")
        private Double temperature = 0.7;

        @JsonProperty("topP")
        private Double topP = 0.9;

        @JsonProperty("timeout")
        private Integer timeout = 60;

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseURL() {
            return baseURL;
        }
        public Double getTemperature() {
            return temperature != null ? temperature : 0.7;
        }

        public void setBaseURL(String baseURL) {
            this.baseURL = baseURL;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Integer getMaxContextTokens() {
            return maxContextTokens;
        }

        public void setMaxContextTokens(Integer maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
        }
    }

    @Data
    public static class ToolsConfig {
        @JsonProperty("fileManager")
        private ToolConfig fileManager = new ToolConfig(); // Ensure fileManager is initialized

        @JsonProperty("commandExec")
        private ToolConfig commandExec = new ToolConfig();

        @JsonProperty("codeExecutor")
        private ToolConfig codeExecutor = new ToolConfig();

        @JsonProperty("search")
        private ToolConfig search = new ToolConfig();

        @JsonProperty("semanticSearch")
        private ToolConfig semanticSearch = new ToolConfig();

        @JsonProperty("codeGraph")
        private ToolConfig codeGraph = new ToolConfig();

        // Getters and Setters
        public ToolConfig getFileManager() {
            if (fileManager == null) {
                fileManager = new ToolConfig(); // Ensure fileManager is not null
            }
            return fileManager;
        }

        public void setFileManager(ToolConfig fileManager) {
            this.fileManager = fileManager;
        }

        public ToolConfig getCommandExec() {
            if (commandExec == null) {
                commandExec = new ToolConfig();
            }
            return commandExec;
        }

        public void setCommandExec(ToolConfig commandExec) {
            this.commandExec = commandExec;
        }

        public ToolConfig getCodeExecutor() {
            if (codeExecutor == null) {
                codeExecutor = new ToolConfig();
            }
            return codeExecutor;
        }

        public void setCodeExecutor(ToolConfig codeExecutor) {
            this.codeExecutor = codeExecutor;
        }

        public ToolConfig getSearch() {
            if (search == null) {
                search = new ToolConfig();
            }
            return search;
        }

        public void setSearch(ToolConfig search) {
            this.search = search;
        }

        public ToolConfig getSemanticSearch() {
            if (semanticSearch == null) {
                semanticSearch = new ToolConfig();
            }
            return semanticSearch;
        }

        public void setSemanticSearch(ToolConfig semanticSearch) {
            this.semanticSearch = semanticSearch;
        }

        public ToolConfig getCodeGraph() {
            if (codeGraph == null) {
                codeGraph = new ToolConfig();
            }
            return codeGraph;
        }

        public void setCodeGraph(ToolConfig codeGraph) {
            this.codeGraph = codeGraph;
        }
    }

    @Data
    public static class ToolConfig {
        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("maxFileSize")
        private Long maxFileSize= 10485760L; // 设置默认值

        @JsonProperty("allowedCommands")
        private String[] allowedCommands;

        @JsonProperty("timeoutSeconds")
        private Integer timeoutSeconds = 30;

        @JsonProperty("allowedLanguages")
        private String[] allowedLanguages = {"java", "python", "javascript"};

        // Getters and Setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Long getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(Long maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public String[] getAllowedCommands() {
            return allowedCommands;
        }

        public void setAllowedCommands(String[] allowedCommands) {
            this.allowedCommands = allowedCommands;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String[] getAllowedLanguages() {
            return allowedLanguages;
        }

        public void setAllowedLanguages(String[] allowedLanguages) {
            this.allowedLanguages = allowedLanguages;
        }
    }

    /**
     * AI行为配置
     */
    @Data
    public static class AIConfig {
        @JsonProperty("autoProcessToolResults")
        private boolean autoProcessToolResults = false; // 默认false：工具执行后直接显示结果，不再反馈给AI

        public boolean isAutoProcessToolResults() {
            return autoProcessToolResults;
        }

        public void setAutoProcessToolResults(boolean autoProcessToolResults) {
            this.autoProcessToolResults = autoProcessToolResults;
        }
    }

    @Data
    public static class SkillConfig {
        @JsonProperty("name")
        private String name;

        @JsonProperty("description")
        private String description;

        @JsonProperty("briefContext")
        private String briefContext;

        @JsonProperty("fullContext")
        private String fullContext;

        @JsonProperty("fullContextPath")
        private String fullContextPath;

        @JsonProperty("inputSchema")
        private Map<String, Object> inputSchema = new HashMap<>();

        @JsonProperty("className")
        private String className;

        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("config")
        private Map<String, Object> config = new HashMap<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getBriefContext() { return briefContext; }
        public void setBriefContext(String briefContext) { this.briefContext = briefContext; }

        public String getFullContext() { return fullContext; }
        public void setFullContext(String fullContext) { this.fullContext = fullContext; }

        public String getFullContextPath() { return fullContextPath; }
        public void setFullContextPath(String fullContextPath) { this.fullContextPath = fullContextPath; }

        public Map<String, Object> getInputSchema() {
            if (inputSchema == null) {
                inputSchema = new HashMap<>();
            }
            return inputSchema;
        }
        public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> config) { this.config = config; }
    }

    @Data
    public static class RagConfig {
        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("autoIndex")
        private boolean autoIndex = true;

        @JsonProperty("workspace")
        private String workspace;

        @JsonProperty("topK")
        private int topK = 5;

        @JsonProperty("baseUrl")
        private String baseUrl;

        @JsonProperty("embeddingModel")
        private String embeddingModel;

        @JsonProperty("chunkSize")
        private int chunkSize = 1000;

        @JsonProperty("chunkOverlap")
        private int chunkOverlap = 200;

        @JsonProperty("cloud")
        private RagCloudConfig cloud = new RagCloudConfig();

        @JsonProperty("apiKey")
        private String apiKey;

        @JsonProperty("cloudStoreUrl")
        private String cloudStoreUrl;

        @JsonProperty("gitnexus")
        private GitNexusConfig gitnexus = new GitNexusConfig();

        @JsonProperty("codeGraph")
        private Map<String, Object> codeGraph = new HashMap<>();

        public RagCloudConfig getCloud() {
            if (cloud == null) {
                cloud = new RagCloudConfig();
            }
            return cloud;
        }

        public void setCloud(RagCloudConfig cloud) {
            this.cloud = cloud;
        }

        public GitNexusConfig getGitnexus() {
            if (gitnexus == null) {
                gitnexus = new GitNexusConfig();
            }
            return gitnexus;
        }

        public void setGitnexus(GitNexusConfig gitnexus) {
            this.gitnexus = gitnexus;
        }

        public Map<String, Object> getCodeGraph() {
            if (codeGraph == null) {
                codeGraph = new HashMap<>();
            }
            return codeGraph;
        }

        public void setCodeGraph(Map<String, Object> codeGraph) {
            this.codeGraph = codeGraph;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getCloudStoreUrl() {
            return cloudStoreUrl;
        }

        public void setCloudStoreUrl(String cloudStoreUrl) {
            this.cloudStoreUrl = cloudStoreUrl;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isAutoIndex() { return autoIndex; }
        public void setAutoIndex(boolean autoIndex) { this.autoIndex = autoIndex; }

        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }

        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    }

    @Data
    public static class RagCloudConfig {
        @JsonProperty("queryEnabled")
        private boolean queryEnabled = true;

        @JsonProperty("minScore")
        private double minScore = 0.7;

        @JsonProperty("authType")
        private String authType;

        @JsonProperty("authHeader")
        private String authHeader;

        @JsonProperty("authKey")
        private String authKey;

        @JsonProperty("timeoutSeconds")
        private int timeoutSeconds = 30;

        @JsonProperty("indexName")
        private String indexName;

        @JsonProperty("queryEndpoint")
        private String queryEndpoint;

        @JsonProperty("endpoint")
        private String endpoint;

        public boolean isQueryEnabled() { return queryEnabled; }
        public void setQueryEnabled(boolean queryEnabled) { this.queryEnabled = queryEnabled; }

        public double getMinScore() { return minScore; }
        public void setMinScore(double minScore) { this.minScore = minScore; }

        public String getAuthType() { return authType; }
        public void setAuthType(String authType) { this.authType = authType; }

        public String getAuthHeader() { return authHeader; }
        public void setAuthHeader(String authHeader) { this.authHeader = authHeader; }

        public String getAuthKey() { return authKey; }
        public void setAuthKey(String authKey) { this.authKey = authKey; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public String getIndexName() { return indexName; }
        public void setIndexName(String indexName) { this.indexName = indexName; }

        public String getQueryEndpoint() { return queryEndpoint; }
        public void setQueryEndpoint(String queryEndpoint) { this.queryEndpoint = queryEndpoint; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }

    @Data
    public static class GitNexusConfig {
        @JsonProperty("serverName")
        private String serverName = "gitnexus";

        @JsonProperty("contextTool")
        private String contextTool = "context";

        @JsonProperty("repo")
        private String repo;

        @JsonProperty("autoConnect")
        private boolean autoConnect = true;

        public String getServerName() {
            return serverName;
        }

        public void setServerName(String serverName) {
            this.serverName = serverName;
        }

        public String getContextTool() {
            return contextTool;
        }

        public void setContextTool(String contextTool) {
            this.contextTool = contextTool;
        }

        public String getRepo() {
            return repo;
        }

        public void setRepo(String repo) {
            this.repo = repo;
        }

        public boolean isAutoConnect() {
            return autoConnect;
        }

        public void setAutoConnect(boolean autoConnect) {
            this.autoConnect = autoConnect;
        }
    }
}
