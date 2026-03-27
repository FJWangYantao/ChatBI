package com.chatbi.service;

public final class PlanningAgentSummaryFormatPolicy {

    private PlanningAgentSummaryFormatPolicy() {
    }

    public static String instructions() {
        return """
            **最终结论排版规则**：
            - 不要使用 Markdown 表格
            - 不要使用 `|` 管道符拼接字段、行或列
            - 不要原样转抄 query_database 的 data_preview、stdout 或 DataFrame 打印结果
            - 优先使用短标题 + 项目符号，项目符号控制在 3-6 条
            - 如果需要列举对象，使用“名称：指标”句式，每条一行
            - 最后补 1 段简短结论，直接回答用户问题
            """;
    }
}
