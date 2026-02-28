package com.chatbi.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 并行子任务执行结果
 */
public class SubTaskResult {
    private final String title;
    private final boolean success;
    private final String code;
    private final String result;
    private final String error;

    private SubTaskResult(String title, boolean success, String code, String result, String error) {
        this.title = title;
        this.success = success;
        this.code = code;
        this.result = result;
        this.error = error;
    }

    public static SubTaskResult success(String title, String result, String code) {
        return new SubTaskResult(title, true, code, result, null);
    }

    public static SubTaskResult failed(String title, String error) {
        return new SubTaskResult(title, false, null, null, error);
    }

    public String getTitle() { return title; }
    public boolean isSuccess() { return success; }
    public String getCode() { return code; }
    public String getResult() { return result; }
    public String getError() { return error; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("success", success);
        if (code != null) map.put("code", code);
        if (result != null) map.put("result", result);
        if (error != null) map.put("error", error);
        return map;
    }
}
