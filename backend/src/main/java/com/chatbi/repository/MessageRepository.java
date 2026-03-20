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
import java.util.Map;
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
                steps JSON COMMENT '处理步骤结果',
                intent_info JSON COMMENT '意图识别结果',
                suggestions JSON COMMENT '推荐后续问题',
                reasoning_steps JSON COMMENT '推理链',
                feedback VARCHAR(20) COMMENT '用户反馈(like/dislike)',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                UNIQUE KEY uk_message_id (message_id),
                INDEX idx_conv_created (conversation_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表'
            """;
        conversationJdbcTemplate.execute(sql);
        // 兼容已有表：添加 steps 列（如果不存在）
        try {
            conversationJdbcTemplate.execute(
                "ALTER TABLE messages ADD COLUMN steps JSON COMMENT '处理步骤结果' AFTER tags"
            );
        } catch (Exception ignored) {
            // 列已存在，忽略
        }
        // 兼容已有表：将单列索引升级为复合索引 (conversation_id, created_at)
        try {
            conversationJdbcTemplate.execute(
                "ALTER TABLE messages DROP INDEX idx_conversation_id, ADD INDEX idx_conv_created (conversation_id, created_at)"
            );
        } catch (Exception ignored) {
            // 旧索引不存在或新索引已存在，忽略
        }
        // 兼容已有表：添加 intent_info 列
        try {
            conversationJdbcTemplate.execute(
                "ALTER TABLE messages ADD COLUMN intent_info JSON COMMENT '意图识别结果' AFTER steps"
            );
        } catch (Exception ignored) {}
        // 兼容已有表：添加 suggestions 列
        try {
            conversationJdbcTemplate.execute(
                "ALTER TABLE messages ADD COLUMN suggestions JSON COMMENT '推荐后续问题' AFTER intent_info"
            );
        } catch (Exception ignored) {}
        // 兼容已有表：添加 reasoning_steps 列
        try {
            conversationJdbcTemplate.execute(
                "ALTER TABLE messages ADD COLUMN reasoning_steps JSON COMMENT '推理链' AFTER suggestions"
            );
        } catch (Exception ignored) {}
        // 兼容已有表：添加 feedback 列
        try {
            conversationJdbcTemplate.execute(
                "ALTER TABLE messages ADD COLUMN feedback VARCHAR(20) COMMENT '用户反馈(like/dislike)' AFTER reasoning_steps"
            );
        } catch (Exception ignored) {}
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

            String stepsJson = rs.getString("steps");
            List<Map<String, Object>> steps = null;
            if (stepsJson != null) {
                try {
                    steps = objectMapper.readValue(
                            stepsJson,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
                    );
                } catch (JsonProcessingException e) {
                    log.warn("steps JSON 解析失败, message_id={}, error={}",
                            rs.getString("message_id"), e.getMessage());
                }
            }

            // 解析 intent_info
            String intentInfoJson = rs.getString("intent_info");
            Map<String, Object> intentInfo = null;
            if (intentInfoJson != null) {
                try {
                    intentInfo = objectMapper.readValue(intentInfoJson, Map.class);
                } catch (JsonProcessingException e) {
                    log.warn("intent_info JSON 解析失败, message_id={}", rs.getString("message_id"));
                }
            }

            // 解析 suggestions
            String suggestionsJson = rs.getString("suggestions");
            List<String> suggestions = null;
            if (suggestionsJson != null) {
                try {
                    suggestions = objectMapper.readValue(
                            suggestionsJson,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    );
                } catch (JsonProcessingException e) {
                    log.warn("suggestions JSON 解析失败, message_id={}", rs.getString("message_id"));
                }
            }

            // 解析 reasoning_steps
            String reasoningStepsJson = rs.getString("reasoning_steps");
            List<Map<String, Object>> reasoningSteps = null;
            if (reasoningStepsJson != null) {
                try {
                    reasoningSteps = objectMapper.readValue(
                            reasoningStepsJson,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
                    );
                } catch (JsonProcessingException e) {
                    log.warn("reasoning_steps JSON 解析失败, message_id={}", rs.getString("message_id"));
                }
            }

            String feedback = rs.getString("feedback");

            return Message.builder()
                    .id(rs.getLong("id"))
                    .messageId(rs.getString("message_id"))
                    .conversationId(rs.getString("conversation_id"))
                    .role(rs.getString("role"))
                    .content(rs.getString("content"))
                    .tags(tags)
                    .steps(steps)
                    .intentInfo(intentInfo)
                    .suggestions(suggestions)
                    .reasoningSteps(reasoningSteps)
                    .feedback(feedback)
                    .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                    .build();
        }
    };

    public Message save(Message message) {
        if (message.getMessageId() == null) {
            // 插入新消息
            String messageId = generateMessageId();
            String tagsJson = serializeTags(message.getTags());
            String stepsJson = serializeSteps(message.getSteps());
            String intentInfoJson = serializeJson(message.getIntentInfo());
            String suggestionsJson = serializeJson(message.getSuggestions());
            String reasoningStepsJson = serializeJson(message.getReasoningSteps());

            conversationJdbcTemplate.update(
                    "INSERT INTO messages (message_id, conversation_id, role, content, tags, steps, intent_info, suggestions, reasoning_steps, feedback) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    messageId, message.getConversationId(), message.getRole(), message.getContent(),
                    tagsJson, stepsJson, intentInfoJson, suggestionsJson, reasoningStepsJson, message.getFeedback()
            );

            return findByMessageId(messageId).orElse(null);
        } else {
            // 更新消息
            String tagsJson = serializeTags(message.getTags());
            String stepsJson = serializeSteps(message.getSteps());
            String intentInfoJson = serializeJson(message.getIntentInfo());
            String suggestionsJson = serializeJson(message.getSuggestions());
            String reasoningStepsJson = serializeJson(message.getReasoningSteps());
            conversationJdbcTemplate.update(
                    "UPDATE messages SET content = ?, tags = ?, steps = ?, intent_info = ?, suggestions = ?, reasoning_steps = ?, feedback = ? WHERE message_id = ?",
                    message.getContent(), tagsJson, stepsJson, intentInfoJson, suggestionsJson, reasoningStepsJson, message.getFeedback(), message.getMessageId()
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

    public void updateFeedback(String messageId, String feedback) {
        conversationJdbcTemplate.update(
                "UPDATE messages SET feedback = ? WHERE message_id = ?",
                feedback, messageId
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

    private String serializeSteps(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String serializeJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
