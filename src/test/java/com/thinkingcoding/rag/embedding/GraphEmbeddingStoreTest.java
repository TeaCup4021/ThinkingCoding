package com.thinkingcoding.rag.embedding;

import com.thinkingcoding.config.AppConfig;
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

    @Test
    void dimensionsFromTypmodShouldDecodeVectorLength() {
        assertEquals(1024, GraphEmbeddingStore.dimensionsFromTypmod(1028));
        assertEquals(3072, GraphEmbeddingStore.dimensionsFromTypmod(3076));
        assertNull(GraphEmbeddingStore.dimensionsFromTypmod(4));
        assertNull(GraphEmbeddingStore.dimensionsFromTypmod(-1));
    }

    @Test
    void shouldExposeConfiguredDimensions() {
        GraphEmbeddingStore store = new GraphEmbeddingStore(new AppConfig.PgVectorConfig(), 1024);
        assertEquals(1024, store.getDimensions());
    }

    @Test
    void upsertShouldValidateVectorDimensionsBeforeDatabaseCall() {
        GraphEmbeddingStore store = new GraphEmbeddingStore(new AppConfig.PgVectorConfig(), 3);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.upsert("foo", new float[]{0.1f, 0.2f}, "Foo.java", "Class", "hash"));

        assertTrue(error.getMessage().contains("expected 3, got 2"));
    }
}
