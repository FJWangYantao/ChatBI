package com.chatbi.repository;

import com.chatbi.entity.Conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 对话Repository
 */
@Repository
public class ConversationRepository {

    private final JdbcTemplate conversationJdbcTemplate;
    private final ObjectMapper objectMapper;

    public ConversationRepository(
            @Qualifier("conversationJdbcTemplate") JdbcTemplate conversationJdbcTemplate,
            ObjectMapper objectMapper) {
        this.conversationJdbcTemplate = conversationJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<Conversation> rowMapper = new RowMapper<Conversation>() {
        @Override
        public Conversation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return Conversation.builder()
                    .id(rs.getLong("id"))
                    .conversationId(rs.getString("conversation_id"))
                    .title(rs.getString("title"))
                    .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                    .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime())
                    .build();
        }
    };

    public Conversation save(Conversation conversation) {
        if (conversation.getConversationId() == null) {
            // 插入新对话
            String conversationId = generateConversationId();
            String title = conversation.getTitle() != null ? conversation.getTitle() : "新对话";

            conversationJdbcTemplate.update(
                    "INSERT INTO conversations (conversation_id, title) VALUES (?, ?)",
                    conversationId, title
            );

            return findByConversationId(conversationId).orElse(null);
        } else {
            // 更新对话
            conversationJdbcTemplate.update(
                    "UPDATE conversations SET title = ?, updated_at = ? WHERE conversation_id = ?",
                    conversation.getTitle(), LocalDateTime.now(), conversation.getConversationId()
            );
            return findByConversationId(conversation.getConversationId()).orElse(null);
        }
    }

    public Optional<Conversation> findByConversationId(String conversationId) {
        String sql = "SELECT * FROM conversations WHERE conversation_id = ?";
        List<Conversation> results = conversationJdbcTemplate.query(sql, rowMapper, conversationId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Conversation> findAll() {
        String sql = "SELECT * FROM conversations ORDER BY updated_at DESC";
        return conversationJdbcTemplate.query(sql, rowMapper);
    }

    public List<Conversation> findAllOrderByUpdatedAtDesc(int limit) {
        String sql = "SELECT * FROM conversations ORDER BY updated_at DESC LIMIT ?";
        return conversationJdbcTemplate.query(sql, rowMapper, limit);
    }

    public void deleteByConversationId(String conversationId) {
        conversationJdbcTemplate.update(
                "DELETE FROM conversations WHERE conversation_id = ?",
                conversationId
        );
    }

    public int count() {
        String sql = "SELECT COUNT(*) FROM conversations";
        Integer count = conversationJdbcTemplate.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    private String generateConversationId() {
        return "conv_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }
}
