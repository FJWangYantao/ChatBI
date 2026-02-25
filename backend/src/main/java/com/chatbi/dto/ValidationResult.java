package com.chatbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置验证结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {
    /**
     * 是否通过验证
     */
    private boolean valid;
    
    /**
     * 错误信息列表（阻止保存）
     */
    private List<String> errors = new ArrayList<>();
    
    /**
     * 警告信息列表（不阻止保存）
     */
    private List<String> warnings = new ArrayList<>();
    
    /**
     * 创建成功的验证结果
     */
    public static ValidationResult success() {
        return new ValidationResult(true, new ArrayList<>(), new ArrayList<>());
    }
    
    /**
     * 创建失败的验证结果
     */
    public static ValidationResult failure(List<String> errors) {
        return new ValidationResult(false, errors, new ArrayList<>());
    }
    
    /**
     * 添加错误
     */
    public void addError(String error) {
        this.errors.add(error);
        this.valid = false;
    }
    
    /**
     * 添加警告
     */
    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
}
