package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ModelInfoResponse {
    private String provider;      // openrouter / deepseek
    private String model;          // 模型名称
    private String baseUrl;        // API 端点
    private Double temperature;    // 温度参数
}
