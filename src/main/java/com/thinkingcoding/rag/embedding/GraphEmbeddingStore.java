package com.thinkingcoding.rag.embedding;

import com.thinkingcoding.config.AppConfig.PgVectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * pgvector 向量存储的 CRUD 操作。
 * 使用 DriverManager 管理连接。
 */
public class GraphEmbeddingStore {
    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingStore.class);
    private static final String SOURCE_FILE_PREFIX = "src/main/java/";
    private static final String INDEX_HNSW = "hnsw";
    private static final String INDEX_IVFFLAT = "ivfflat";

    private final PgVectorConfig config;
    private final int dimensions;
    private final String vectorIndex;
    private final int hnswM;
    private final int hnswEfConstruction;
    private final int hnswEfSearch;

    public GraphEmbeddingStore(PgVectorConfig config, int dimensions) {
        this(config, dimensions, INDEX_HNSW, 16, 64, 40);
    }

    public GraphEmbeddingStore(PgVectorConfig config, int dimensions, String vectorIndex,
                               int hnswM, int hnswEfConstruction, int hnswEfSearch) {
        this.config = config;
        this.dimensions = dimensions;
        this.vectorIndex = normalizeVectorIndex(vectorIndex);
        this.hnswM = Math.max(2, hnswM);
        this.hnswEfConstruction = Math.max(1, hnswEfConstruction);
        this.hnswEfSearch = Math.max(1, hnswEfSearch);
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                config.buildJdbcUrl(),
                config.getUser(),
                config.getPassword()
        );
    }

    /** 建表（幂等）。 */
    public void ensureTable() {
        String createExtension = "CREATE EXTENSION IF NOT EXISTS vector";
        String createTable = "CREATE TABLE IF NOT EXISTS " + tableName() + " ("
                + "qualified_name TEXT PRIMARY KEY, "
                + "vector vector(" + dimensions + "), "
                + "file_path TEXT, "
                + "kind TEXT, "
                + "git_commit_hash TEXT, "
                + "indexed_at TIMESTAMP DEFAULT NOW()"
                + ")";

        try (Connection conn = getConnection();
             PreparedStatement ext = conn.prepareStatement(createExtension);
             PreparedStatement tbl = conn.prepareStatement(createTable)) {
            ext.execute();
            tbl.execute();
            ensureIndex(conn);
            log.info("Table {} ensured ({} dims)", tableName(), dimensions);
        } catch (SQLException e) {
            log.error("Failed to ensure table: {}", e.getMessage());
        }
    }

    public Integer readExistingDimensions() {
        String sql = "SELECT a.atttypmod "
                + "FROM pg_attribute a "
                + "WHERE a.attrelid = to_regclass(?) "
                + "AND a.attname = 'vector' "
                + "AND NOT a.attisdropped";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return dimensionsFromTypmod(rs.getInt("atttypmod"));
                }
            }
        } catch (SQLException e) {
            log.debug("Could not read existing vector dimensions: {}", e.getMessage());
        }
        return null;
    }

    private void ensureIndex(Connection conn) throws SQLException {
        String indexName = vectorIndexName();
        // 检查索引是否存在
        String checkSql = "SELECT 1 FROM pg_indexes WHERE indexname = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        // 创建 IVF 索引（仅在表不为空时有效）
        String createIndex = defaultCreateIndexSql(indexName);
        try (PreparedStatement ps = conn.prepareStatement(createIndex)) {
            ps.execute();
        }
    }

    public void rebuildIvfflatIndex(int lists) throws SQLException {
        if (lists < 1) {
            throw new IllegalArgumentException("lists must be positive");
        }
        String createIndex = "CREATE INDEX IF NOT EXISTS " + vectorIndexName()
                + " ON " + tableName()
                + " USING ivfflat (vector vector_cosine_ops) WITH (lists = " + lists + ")";
        rebuildVectorIndex(createIndex);
    }

    public void rebuildHnswIndex(int m, int efConstruction) throws SQLException {
        if (m < 2) {
            throw new IllegalArgumentException("m must be at least 2");
        }
        if (efConstruction < 1) {
            throw new IllegalArgumentException("efConstruction must be positive");
        }
        String createIndex = "CREATE INDEX IF NOT EXISTS " + vectorIndexName()
                + " ON " + tableName()
                + " USING hnsw (vector vector_cosine_ops) WITH (m = " + m
                + ", ef_construction = " + efConstruction + ")";
        rebuildVectorIndex(createIndex);
    }

    private void rebuildVectorIndex(String createIndexSql) throws SQLException {
        try (Connection conn = getConnection()) {
            try (PreparedStatement drop = conn.prepareStatement("DROP INDEX IF EXISTS " + vectorIndexName())) {
                drop.execute();
            }
            try (PreparedStatement create = conn.prepareStatement(createIndexSql)) {
                create.execute();
            }
        }
    }

    public void rebuildDefaultIndex() throws SQLException {
        if (INDEX_HNSW.equals(vectorIndex)) {
            rebuildHnswIndex(hnswM, hnswEfConstruction);
        } else {
            rebuildIvfflatIndex(50);
        }
    }

    /** 插入或更新嵌入。 */
    public void upsert(String qualifiedName, float[] vector,
                       String filePath, String kind, String gitCommitHash) {
        validateVectorDimensions(vector);
        String sql = "INSERT INTO " + tableName()
                + " (qualified_name, vector, file_path, kind, git_commit_hash, indexed_at) "
                + "VALUES (?, ?::vector, ?, ?, ?, NOW()) "
                + "ON CONFLICT (qualified_name) DO UPDATE SET "
                + "vector = EXCLUDED.vector, file_path = EXCLUDED.file_path, "
                + "kind = EXCLUDED.kind, git_commit_hash = EXCLUDED.git_commit_hash, "
                + "indexed_at = NOW()";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qualifiedName);
            ps.setString(2, vectorToString(vector));
            ps.setString(3, filePath);
            ps.setString(4, kind);
            ps.setString(5, gitCommitHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Upsert failed for {}: {}", qualifiedName, e.getMessage());
        }
    }

    /** 余弦相似度搜索。 */
    public List<SearchResult> search(float[] queryVector, int topK) {
        validateVectorDimensions(queryVector);
        String sql = "SELECT qualified_name, file_path, kind, "
                + "1 - (vector <=> ?::vector) AS similarity "
                + "FROM " + tableName()
                + " ORDER BY vector <=> ?::vector LIMIT ?";

        List<SearchResult> results = new ArrayList<>();
        String vecStr = vectorToString(queryVector);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vecStr);
            ps.setString(2, vecStr);
            ps.setInt(3, topK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SearchResult(
                            rs.getString("qualified_name"),
                            rs.getString("file_path"),
                            rs.getString("kind"),
                            rs.getDouble("similarity")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("Search failed: {}", e.getMessage());
        }
        return results;
    }

    public List<SearchResult> searchSourceOnly(float[] queryVector, int topK) {
        return searchWithSourceFilter(queryVector, topK, null);
    }

    /** 精确余弦相似度搜索：禁用 ANN 索引扫描，用作 RAG 向量检索上限基线。 */
    public List<SearchResult> searchDefault(float[] queryVector, int topK) throws SQLException {
        if (INDEX_HNSW.equals(vectorIndex)) {
            return searchHnsw(queryVector, topK, hnswEfSearch);
        }
        return search(queryVector, topK);
    }

    public List<SearchResult> searchExact(float[] queryVector, int topK) {
        validateVectorDimensions(queryVector);
        String sql = "SELECT qualified_name, file_path, kind, "
                + "1 - (vector <=> ?::vector) AS similarity "
                + "FROM " + tableName()
                + " ORDER BY vector <=> ?::vector LIMIT ?";

        List<SearchResult> results = new ArrayList<>();
        String vecStr = vectorToString(queryVector);
        try (Connection conn = getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement disableIndexScan = conn.prepareStatement("SET LOCAL enable_indexscan = off");
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                disableIndexScan.execute();
                ps.setString(1, vecStr);
                ps.setString(2, vecStr);
                ps.setInt(3, topK);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(new SearchResult(
                                rs.getString("qualified_name"),
                                rs.getString("file_path"),
                                rs.getString("kind"),
                                rs.getDouble("similarity")
                        ));
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            log.error("Exact search failed: {}", e.getMessage());
        }
        return results;
    }

    public List<SearchResult> searchExactSourceOnly(float[] queryVector, int topK) {
        return searchWithSourceFilter(queryVector, topK, "SET LOCAL enable_indexscan = off");
    }

    public List<SearchResult> searchIvfflat(float[] queryVector, int topK, int probes) throws SQLException {
        if (probes < 1) {
            throw new IllegalArgumentException("probes must be positive");
        }
        return searchWithLocalSetting(queryVector, topK, "SET LOCAL ivfflat.probes = " + probes);
    }

    public List<SearchResult> searchIvfflatSourceOnly(float[] queryVector, int topK, int probes) throws SQLException {
        if (probes < 1) {
            throw new IllegalArgumentException("probes must be positive");
        }
        return searchWithSourceFilter(queryVector, topK, "SET LOCAL ivfflat.probes = " + probes);
    }

    public List<SearchResult> searchHnsw(float[] queryVector, int topK, int efSearch) throws SQLException {
        if (efSearch < 1) {
            throw new IllegalArgumentException("efSearch must be positive");
        }
        return searchWithLocalSetting(queryVector, topK, "SET LOCAL hnsw.ef_search = " + efSearch);
    }

    public List<SearchResult> searchHnswSourceOnly(float[] queryVector, int topK, int efSearch) throws SQLException {
        if (efSearch < 1) {
            throw new IllegalArgumentException("efSearch must be positive");
        }
        return searchWithSourceFilter(queryVector, topK, "SET LOCAL hnsw.ef_search = " + efSearch);
    }

    private List<SearchResult> searchWithLocalSetting(
            float[] queryVector,
            int topK,
            String localSettingSql
    ) throws SQLException {
        validateVectorDimensions(queryVector);
        String sql = "SELECT qualified_name, file_path, kind, "
                + "1 - (vector <=> ?::vector) AS similarity "
                + "FROM " + tableName()
                + " ORDER BY vector <=> ?::vector LIMIT ?";

        List<SearchResult> results = new ArrayList<>();
        String vecStr = vectorToString(queryVector);
        try (Connection conn = getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement setting = conn.prepareStatement(localSettingSql);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                setting.execute();
                ps.setString(1, vecStr);
                ps.setString(2, vecStr);
                ps.setInt(3, topK);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(new SearchResult(
                                rs.getString("qualified_name"),
                                rs.getString("file_path"),
                                rs.getString("kind"),
                                rs.getDouble("similarity")
                        ));
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
        return results;
    }

    private List<SearchResult> searchWithSourceFilter(
            float[] queryVector,
            int topK,
            String localSettingSql
    ) {
        validateVectorDimensions(queryVector);
        String sql = "SELECT qualified_name, file_path, kind, "
                + "1 - (vector <=> ?::vector) AS similarity "
                + "FROM " + tableName()
                + " WHERE replace(file_path, '\\\\', '/') LIKE ? "
                + "ORDER BY vector <=> ?::vector LIMIT ?";

        List<SearchResult> results = new ArrayList<>();
        String vecStr = vectorToString(queryVector);
        try (Connection conn = getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement setting = localSettingSql == null ? null : conn.prepareStatement(localSettingSql);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                if (setting != null) {
                    setting.execute();
                }
                ps.setString(1, vecStr);
                ps.setString(2, SOURCE_FILE_PREFIX + "%");
                ps.setString(3, vecStr);
                ps.setInt(4, topK);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(new SearchResult(
                                rs.getString("qualified_name"),
                                rs.getString("file_path"),
                                rs.getString("kind"),
                                rs.getDouble("similarity")
                        ));
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            log.error("Source-only search failed: {}", e.getMessage());
        }
        return results;
    }

    /** 获取当前存储的 git commit hash（任意一条记录的 hash）。 */
    public String getStoredCommitHash() {
        String sql = "SELECT git_commit_hash FROM " + tableName() + " LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getString("git_commit_hash");
        } catch (SQLException e) {
            log.debug("Could not read commit hash: {}", e.getMessage());
        }
        return null;
    }

    /** 检查某符号是否已有嵌入。 */
    public boolean exists(String qualifiedName) {
        String sql = "SELECT 1 FROM " + tableName() + " WHERE qualified_name = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qualifiedName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** 按名称删除。 */
    public void delete(String qualifiedName) {
        String sql = "DELETE FROM " + tableName() + " WHERE qualified_name = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qualifiedName);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Delete failed for {}: {}", qualifiedName, e.getMessage());
        }
    }

    /** 按文件路径批量查找符号名。 */
    public List<String> findByFilePaths(Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return List.of();

        String placeholders = filePaths.stream().map(f -> "?").collect(Collectors.joining(","));
        String sql = "SELECT qualified_name FROM " + tableName()
                + " WHERE file_path IN (" + placeholders + ")";
        List<String> result = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String fp : filePaths) {
                ps.setString(i++, fp);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("qualified_name"));
                }
            }
            log.debug("Found {} symbols in {} files", result.size(), filePaths.size());
        } catch (SQLException e) {
            log.error("Failed to find by file paths: {}", e.getMessage());
        }
        return result;
    }

    /** 按文件路径批量删除。 */
    public int deleteByFilePaths(Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return 0;

        String placeholders = filePaths.stream().map(f -> "?").collect(Collectors.joining(","));
        String sql = "DELETE FROM " + tableName() + " WHERE file_path IN (" + placeholders + ")";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String fp : filePaths) {
                ps.setString(i++, fp);
            }
            int deleted = ps.executeUpdate();
            log.info("Deleted {} embeddings for {} changed files", deleted, filePaths.size());
            return deleted;
        } catch (SQLException e) {
            log.error("Failed to delete by file paths: {}", e.getMessage());
            return 0;
        }
    }

    /** 清空表。 */
    public void clear() {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tableName())) {
            int deleted = ps.executeUpdate();
            log.info("Cleared {} records from {}", deleted, tableName());
        } catch (SQLException e) {
            log.error("Clear failed: {}", e.getMessage());
        }
    }

    public int count() {
        String sql = "SELECT COUNT(*) FROM " + tableName();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Count failed: {}", e.getMessage());
        }
        return 0;
    }

    public int countSourceOnly() {
        String sql = "SELECT COUNT(*) FROM " + tableName()
                + " WHERE replace(file_path, '\\\\', '/') LIKE ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SOURCE_FILE_PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Source-only count failed: {}", e.getMessage());
        }
        return 0;
    }

    public boolean isEmpty() {
        return count() == 0;
    }

    public int getDimensions() {
        return dimensions;
    }

    private String tableName() {
        String schema = config.getSchema();
        if (schema != null && !schema.isBlank() && !"public".equals(schema)) {
            return schema + ".graph_embeddings";
        }
        return "graph_embeddings";
    }

    private String vectorIndexName() {
        return tableName() + "_vector_idx";
    }

    /** float[] → pgvector 兼容字符串 "[0.1,0.2,...]" */
    private String defaultCreateIndexSql(String indexName) {
        if (INDEX_HNSW.equals(vectorIndex)) {
            return "CREATE INDEX IF NOT EXISTS " + indexName
                    + " ON " + tableName()
                    + " USING hnsw (vector vector_cosine_ops) WITH (m = " + hnswM
                    + ", ef_construction = " + hnswEfConstruction + ")";
        }
        return "CREATE INDEX IF NOT EXISTS " + indexName
                + " ON " + tableName()
                + " USING ivfflat (vector vector_cosine_ops) WITH (lists = 50)";
    }

    private static String normalizeVectorIndex(String value) {
        if (value == null || value.isBlank()) {
            return INDEX_HNSW;
        }
        String normalized = value.trim().toLowerCase();
        if (INDEX_IVFFLAT.equals(normalized)) {
            return INDEX_IVFFLAT;
        }
        return INDEX_HNSW;
    }

    static String vectorToString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    static Integer dimensionsFromTypmod(int typmod) {
        return typmod > 4 ? typmod - 4 : null;
    }

    private void validateVectorDimensions(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("Embedding vector must not be null");
        }
        if (dimensions > 0 && vector.length != dimensions) {
            throw new IllegalArgumentException("Embedding vector dimension mismatch: expected "
                    + dimensions + ", got " + vector.length
                    + ". Rebuild the RAG index or align rag.dimensions with the embedding model.");
        }
    }

    public record SearchResult(String qualifiedName, String filePath,
                                String kind, double similarity) {}
}
