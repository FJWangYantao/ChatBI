package com.chatbi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningAgentSummaryFormatPolicyTest {

    @Test
    void finalSummaryInstructionsForbidTablesAndPipeDelimitedOutput() {
        String policy = PlanningAgentSummaryFormatPolicy.instructions();

        assertTrue(policy.contains("不要使用 Markdown 表格"), "policy should explicitly forbid markdown tables");
        assertTrue(policy.contains("不要使用"), "policy should contain direct negative constraints");
        assertTrue(policy.contains("|"), "policy should mention pipe-delimited output explicitly");
        assertTrue(policy.contains("项目符号"), "policy should steer the model toward bullet formatting");
    }
}
