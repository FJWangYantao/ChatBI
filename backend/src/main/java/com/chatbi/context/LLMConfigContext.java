package com.chatbi.context;

/**
 * LLM 配置上下文管理器
 * 使用 InheritableThreadLocal 存储当前请求的 LLM 配置，支持异步线程继承
 */
public class LLMConfigContext {
    private static final InheritableThreadLocal<LLMConfig> context = new InheritableThreadLocal<>();

    public static void set(LLMConfig config) {
        context.set(config);
    }

    public static LLMConfig get() {
        return context.get();
    }

    public static void clear() {
        context.remove();
    }

    /**
     * LLM 配置信息
     */
    public static class LLMConfig {
        private String provider;
        private String apiKey;
        private String modelName;
        private String baseUrl;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
