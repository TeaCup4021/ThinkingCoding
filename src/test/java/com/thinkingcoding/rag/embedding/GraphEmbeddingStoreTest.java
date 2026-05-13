package com.thinkingcoding.rag.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GraphEmbeddingStoreTest {

    @Test
    void vectorToStringShouldFormatCorrectly() {
        float[] vec = {0.1f, 0.2f, 0.3f};
        String result = GraphEmbeddingStore.vectorToString(vec);
        assertEquals("[0.1,0.2,0.3]", result);
    }

    @Test
    void vectorToStringShouldHandleEmpty() {
        float[] vec = {};
        String result = GraphEmbeddingStore.vectorToString(vec);
        assertEquals("[]", result);
    }

    @Test
    void vectorToStringShouldHandleSingle() {
        float[] vec = {1.5f};
        String result = GraphEmbeddingStore.vectorToString(vec);
        assertEquals("[1.5]", result);
    }

    @Test
    void searchResultRecordShouldWork() {
        GraphEmbeddingStore.SearchResult r = new GraphEmbeddingStore.SearchResult(
                "com.example.Foo", "src/Foo.java", "CLASS", 0.95);
        assertEquals("com.example.Foo", r.qualifiedName());
        assertEquals("src/Foo.java", r.filePath());
        assertEquals("CLASS", r.kind());
        assertEquals(0.95, r.similarity(), 0.001);
    }
}
