package com.chatbi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 意图识别请求 DTO
 */
public class IntentRecognitionRequest {
    /**
     * 待识别的文本
     */
    private String text;

    /**
     * 是否返回概率分布
     */
    @JsonProperty("return_probs")
    private boolean returnProbs = false;

    public IntentRecognitionRequest() {
    }

    public IntentRecognitionRequest(String text) {
        this.text = text;
    }

    public IntentRecognitionRequest(String text, boolean returnProbs) {
        this.text = text;
        this.returnProbs = returnProbs;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public boolean isReturnProbs() {
        return returnProbs;
    }

    public void setReturnProbs(boolean returnProbs) {
        this.returnProbs = returnProbs;
    }
}
