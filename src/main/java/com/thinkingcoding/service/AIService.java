package com.thinkingcoding.service;

import com.thinkingcoding.model.ChatMessage;
import com.thinkingcoding.model.ToolCall;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 服务接口，定义了与 AI 模型交互的方法
 */
public interface AIService {
    /**
     * 执行同步对话请求，等待完整响应后返回
     *
     * @param input 用户输入内容
     * @param history 历史对话消息列表，提供上下文信息
     * @param modelName 使用的AI模型名称
     * @return 包含AI响应的完整消息列表
     */
    List<ChatMessage> chat(String input, List<ChatMessage> history, String modelName);
    
    /**
     * 执行流式对话请求，实时处理AI响应片段
     *
     * @param input 用户输入内容
     * @param history 历史对话消息列表，提供上下文信息
     * @param modelName 使用的AI模型名称
     * @return 包含AI响应的完整消息列表
     */
    List<ChatMessage> streamingChat(String input, List<ChatMessage> history, String modelName);
    
    /**
     * 设置消息处理器，用于接收AI生成的消息片段或完整消息
     *
     * @param handler 消息消费函数，处理接收到的ChatMessage
     */
    void setMessageHandler(Consumer<ChatMessage> handler);
    
    /**
     * 设置工具调用处理器，用于接收AI触发的工具调用请求
     *
     * @param handler 工具调用消费函数，处理接收到的ToolCall
     */
    void setToolCallHandler(Consumer<ToolCall> handler);
    
    /**
     * 验证指定的模型名称是否可用和有效
     *
     * @param modelName 待验证的模型名称
     * @return 如果模型有效且可用则返回true，否则返回false
     */
    boolean validateModel(String modelName);
    
    /**
     * 获取当前配置中所有可用的AI模型列表
     *
     * @return 可用模型名称的字符串列表
     */
    List<String> getAvailableModels();

    /**
     * 直接调用模型，不注入Agent上下文和工具列表。
     * 适用于 Skill 内部需要干净模型响应的场景（如代码生成）。
     *
     * @param systemPrompt 系统提示
     * @param userPrompt 用户提示
     * @param modelName 模型名称
     * @return 模型完整文本响应
     */
    String directChat(String systemPrompt, String userPrompt, String modelName);
}