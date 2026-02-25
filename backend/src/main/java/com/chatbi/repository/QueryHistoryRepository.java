package com.chatbi.repository;

import com.chatbi.entity.QueryHistory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 查询历史Repository
 */
@Repository
public class QueryHistoryRepository {

    private final JdbcTemplate conversationJdbcTemplate;

    public QueryHistoryRepository(
            @Qualifier("conversationJdbcTemplate") JdbcTemplate conversationJdbcTemplate) {
        this.conversationJdbcTemplate = conversationJdbcTemplate;
    }

    private final RowMapper<QueryHistory> rowMapper = new RowMapper<QueryHistory>() {
        @Override
        public QueryHistory mapRow(ResultSet rs, int rowNum) throws SQLException {
            return QueryHistory.builder()
                    .id(rs.getLong("id"))
                    .messageId(rs.getString("message_id"))
                    .conversationId(rs.getString("conversation_id"))
                    .sqlText(rs.getString("sql_text"))
                    .executionTime(rs.getLong("execution_time"))
                    .rowsAffected(rs.getInt("rows_affected"))
                    .success(rs.getBoolean("success"))
                    .errorMessage(rs.getString("error_message"))
                    .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                    .build();
        }
    };

    public QueryHistory save(QueryHistory queryHistory) {
        conversationJdbcTemplate.update(
                "INSERT INTO query_history (message_id, conversation_id, sql_text, execution_time, rows_affected, success, error_message) VALUES (?, ?, ?, ?, ?, ?, ?)",
                queryHistory.getMessageId(),
                queryHistory.getConversationId(),
                queryHistory.getSqlText(),
                queryHistory.getExecutionTime(),
                queryHistory.getRowsAffected(),
                queryHistory.getSuccess(),
                queryHistory.getErrorMessage()
        );
        return queryHistory;
    }

    public List<QueryHistory> findByConversationId(String conversationId) {
        String sql = "SELECT * FROM query_history WHERE conversation_id = ? ORDER BY created_at DESC";
        return conversationJdbcTemplate.query(sql, rowMapper, conversationId);
    }

    public List<QueryHistory> findByMessageId(String messageId) {
        String sql = "SELECT * FROM query_history WHERE message_id = ? ORDER BY created_at DESC";
        return conversationJdbcTemplate.query(sql, rowMapper, messageId);
    }

    public List<QueryHistory> findAll() {
        String sql = "SELECT * FROM query_history ORDER BY created_at DESC";
        return conversationJdbcTemplate.query(sql, rowMapper);
    }
}
