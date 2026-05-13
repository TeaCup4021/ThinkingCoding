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
import java.util.List;

/**
 * pgvector 向量存储的 CRUD 操作。
 * 使用 DriverManager 管理连接。
 */
public class GraphEmbeddingStore {
    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingStore.class);

    private final PgVectorConfig config;
    private final int dimensions;

    public GraphEmbeddingStore(PgVectorConfig config, int dimensions) {
        this.config = config;
        this.dimensions = dimensions;
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

    private void ensureIndex(Connection conn) throws SQLException {
        String indexName = tableName() + "_vector_idx";
        // 检查索引是否存在
        String checkSql = "SELECT 1 FROM pg_indexes WHERE indexname = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        // 创建 IVF 索引（仅在表不为空时有效）
        String createIndex = "CREATE INDEX IF NOT EXISTS " + indexName
                + " ON " + tableName()
                + " USING ivfflat (vector vector_cosine_ops) WITH (lists = 50)";
        try (PreparedStatement ps = conn.prepareStatement(createIndex)) {
            ps.execute();
        }
    }

    /** 插入或更新嵌入。 */
    public void upsert(String qualifiedName, float[] vector,
                       String filePath, String kind, String gitCommitHash) {
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

    public boolean isEmpty() {
        return count() == 0;
    }

    private String tableName() {
        String schema = config.getSchema();
        if (schema != null && !schema.isBlank() && !"public".equals(schema)) {
            return schema + ".graph_embeddings";
        }
        return "graph_embeddings";
    }

    /** float[] → pgvector 兼容字符串 "[0.1,0.2,...]" */
    static String vectorToString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    public record SearchResult(String qualifiedName, String filePath,
                                String kind, double similarity) {}
}