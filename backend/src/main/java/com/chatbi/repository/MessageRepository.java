package com.chatbi.repository;

import com.chatbi.entity.Message;
import com.chatbi.dto.MessageTag;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 消息Repository
 */
@Repository
public class MessageRepository {

    private static final Logger log = LoggerFactory.getLogger(MessageRepository.class);

    private final JdbcTemplate conversationJdbcTemplate;
    private final ObjectMapper objectMapper;

    public MessageRepository(
            @Qualifier("conversationJdbcTemplate") JdbcTemplate conversationJdbcTemplate,
            ObjectMapper objectMapper) {
        this.conversationJdbcTemplate = conversationJdbcTemplate;
        this.objectMapper = objectMapper;
        initTable();
    }

    /**
     * 初始化消息表
     */
    private void initTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS messages (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                message_id VARCHAR(100) NOT NULL COMMENT '消息ID',
                conversation_id VARCHAR(100) NOT NULL COMMENT '对话ID',
                role VARCHAR(20) NOT NULL COMMENT '角色(user/assistant)',
                content TEXT COMMENT '消息内容',
                tags JSON COMMENT '标签数据',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                UNIQUE KEY uk_message_id (message_id),
                INDEX idx_conversation_id (conversation_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表'
            """;
        conversationJdbcTemplate.execute(sql);
    }

    private final RowMapper<Message> rowMapper = new RowMapper<Message>() {
        @Override
        public Message mapRow(ResultSet rs, int rowNum) throws SQLException {
            String tagsJson = rs.getString("tags");
            List<MessageTag> tags = null;
            if (tagsJson != null) {
                try {
                    tags = objectMapper.readValue(
                            tagsJson,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, MessageTag.class)
                    );
                } catch (JsonProcessingException e) {
                    log.warn("tags JSON 解析失败, message_id={}, tagsJson={}, error={}",
                            rs.getString("message_id"), tagsJson, e.getMessage());
                }
            }

            return Message.builder()
                    .id(rs.getLong("id"))
                    .messageId(rs.getString("message_id"))
                    .conversationId(rs.getString("conversation_id"))
                    .role(rs.getString("role"))
                    .content(rs.getString("content"))
                    .tags(tags)
                    .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                    .build();
        }
    };

    public Message save(Message message) {
        if (message.getMessageId() == null) {
            // 插入新消息
            String messageId = generateMessageId();
            String tagsJson = serializeTags(message.getTags());

            conversationJdbcTemplate.update(
                    "INSERT INTO messages (message_id, conversation_id, role, content, tags) VALUES (?, ?, ?, ?, ?)",
                    messageId, message.getConversationId(), message.getRole(), message.getContent(), tagsJson
            );

            return findByMessageId(messageId).orElse(null);
        } else {
            // 更新消息
            String tagsJson = serializeTags(message.getTags());
            conversationJdbcTemplate.update(
                    "UPDATE messages SET content = ?, tags = ? WHERE message_id = ?",
                    message.getContent(), tagsJson, message.getMessageId()
            );
            return findByMessageId(message.getMessageId()).orElse(null);
        }
    }

    public Optional<Message> findByMessageId(String messageId) {
        String sql = "SELECT * FROM messages WHERE message_id = ?";
        List<Message> results = conversationJdbcTemplate.query(sql, rowMapper, messageId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Message> findByConversationId(String conversationId) {
        String sql = "SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at ASC";
        return conversationJdbcTemplate.query(sql, rowMapper, conversationId);
    }

    public void deleteByMessageId(String messageId) {
        conversationJdbcTemplate.update(
                "DELETE FROM messages WHERE message_id = ?",
                messageId
        );
    }

    public void deleteByConversationId(String conversationId) {
        conversationJdbcTemplate.update(
                "DELETE FROM messages WHERE conversation_id = ?",
                conversationId
        );
    }

    public int countByConversationId(String conversationId) {
        String sql = "SELECT COUNT(*) FROM messages WHERE conversation_id = ?";
        Integer count = conversationJdbcTemplate.queryForObject(sql, Integer.class, conversationId);
        return count != null ? count : 0;
    }

    private String generateMessageId() {
        return "msg_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }

    private String serializeTags(List<MessageTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
