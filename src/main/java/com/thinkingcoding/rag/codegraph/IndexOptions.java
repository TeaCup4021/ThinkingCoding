package com.thinkingcoding.rag.codegraph;

public final class IndexOptions {
    private final boolean includeTests;
    private final long maxFileSizeBytes;

    public IndexOptions(boolean includeTests, long maxFileSizeBytes) {
        this.includeTests = includeTests;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public boolean isIncludeTests() {
        return includeTests;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }
}


