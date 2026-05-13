package com.thinkingcoding.rag.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 Embedding API 客户端。
 * 使用 text-embedding-3-large (3072 维)。
 */
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final OkHttpClient client;

    public EmbeddingService(String baseUrl, String apiKey, String modelName) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * 嵌入单个文本，返回 float[].
     */
    public float[] embed(String text) throws IOException {
        float[][] batch = embedBatch(List.of(text));
        if (batch.length == 0) throw new IOException("Empty embedding response");
        return batch[0];
    }

    /**
     * 批量嵌入，返回 float[][].
     */
    public float[][] embedBatch(List<String> texts) throws IOException {
        if (texts.isEmpty()) return new float[0][];

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("input", texts.size() == 1 ? texts.get(0) : texts);
        body.put("encoding_format", "float");

        String json = mapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("Embedding API error " + response.code() + ": " + errorBody);
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(responseBody, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null || data.isEmpty()) {
                throw new IOException("Empty embedding data in response");
            }

            float[][] embeddings = new float[data.size()][];
            for (int i = 0; i < data.size(); i++) {
                @SuppressWarnings("unchecked")
                List<Number> raw = (List<Number>) data.get(i).get("embedding");
                if (raw == null) throw new IOException("Missing embedding at index " + i);
                float[] vec = new float[raw.size()];
                for (int j = 0; j < raw.size(); j++) {
                    vec[j] = raw.get(j).floatValue();
                }
                embeddings[i] = vec;
            }

            Object usage = result.get("usage");
            if (usage instanceof Map<?, ?> u) {
                log.debug("Embedding tokens: {}", u.get("total_tokens"));
            }

            return embeddings;
        }
    }

    public String getModelName() { return modelName; }
}