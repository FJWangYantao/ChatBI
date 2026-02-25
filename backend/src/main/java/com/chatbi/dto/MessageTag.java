package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息标签 - 用于扩展消息内容类型
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MessageTag {
    /**
     * 标签类型：sql, table, chart, image 等
     */
    private String type;

    /**
     * 标签内容
     */
    private Object content;

    /**
     * 标签标题（可选）
     */
    private String title;

    /**
     * 额外元数据（可选）
     */
    private Object metadata;
}
