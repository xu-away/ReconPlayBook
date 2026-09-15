package com.reconplaybook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconEngineTest {
    private static final String LOCAL = """
            merchant_order_no,amount,currency,status,fee,transaction_time
            A-1,10.00,CNY,SUCCESS,0.10,2026-09-12 09:00:00
            A-2,20.00,CNY,SUCCESS,0.20,2026-09-12 09:01:00
            """;

    private static final String CHANNEL = """
            [
              {"out_trade_no":"A-1","transaction_id":"T-1","total_amount":"10.00","currency_code":"CNY","trade_status":"paid","service_fee":"0.10","paid_at":"2026-09-12T09:00:05+08:00"},
              {"out_trade_no":"A-2","transaction_id":"T-2","total_amount":"21.00","currency_code":"CNY","trade_status":"paid","service_fee":"0.20","paid_at":"2026-09-12T09:01:05+08:00"}
            ]
            """;

    @BeforeEach
    void resetWorkflowStore() {
        WorkflowStore.clearForTests();
    }

    @Test
    void normalizesAliasesAndClassifiesAmountMismatch() throws Exception {
        Map<String, Object> result = ReconEngine.reconcile(
                "local.csv", "csv", LOCAL, "channel.json", "json", CHANNEL);

        Map<String, Long> byType = (Map<String, Long>) ((Map<?, ?>) result.get("stats")).get("byType");
        assertEquals(1L, byType.get("MATCHED"));
        assertEquals(1L, byType.get("AMOUNT_MISMATCH"));
        Map<?, ?> mismatch = ((java.util.List<Map<String, Object>>) result.get("results")).stream()
                .filter(item -> "AMOUNT_MISMATCH".equals(item.get("discrepancyType")))
                .findFirst().orElseThrow();
        Map<?, ?> diagnosis = (Map<?, ?>) mismatch.get("diagnosis");
        assertEquals("HIGH", diagnosis.get("confidence"));
        assertTrue(String.valueOf(diagnosis.get("primaryCause")).contains("金额"));
        assertTrue(((java.util.List<?>) diagnosis.get("nextChecks")).size() >= 2);
        Map<?, ?> playbook = (Map<?, ?>) mismatch.get("playbook");
        assertEquals("AMOUNT_DISCOUNT_OR_REFUND_REVIEW", playbook.get("code"));
        assertEquals("HIGH", playbook.get("riskLevel"));
        assertTrue(((List<?>) playbook.get("processingPath")).size() >= 3);
        assertTrue(((List<?>) playbook.get("forbiddenActions")).contains("直接修改账务金额"));
    }

    @Test
    void recognizesUnmatchedLocalAndChannelRecords() throws Exception {
        Map<String, Object> result = ReconEngine.reconcile(
                "local.csv", "csv", "merchant_order_no,amount\nLOCAL-ONLY,3.00\n",
                "channel.csv", "csv", "out_trade_no,total_amount\nCHANNEL-ONLY,4.00\n");

        Map<String, Long> byType = (Map<String, Long>) ((Map<?, ?>) result.get("stats")).get("byType");
        assertEquals(1L, byType.get("UNMATCHED_LOCAL"));
        assertEquals(1L, byType.get("UNMATCHED_CHANNEL"));
        assertTrue(((Number) ((Map<?, ?>) result.get("stats")).get("attention")).intValue() == 2);
        Map<?, ?> localCase = ((java.util.List<Map<String, Object>>) result.get("results")).stream()
                .filter(item -> "UNMATCHED_LOCAL".equals(item.get("discrepancyType")))
                .findFirst().orElseThrow();
        Map<?, ?> diagnosis = (Map<?, ?>) localCase.get("diagnosis");
        assertEquals("MEDIUM", diagnosis.get("confidence"));
        assertTrue(((java.util.List<?>) diagnosis.get("blockedActions")).contains("重复发起扣款"));
        Map<?, ?> playbook = (Map<?, ?>) localCase.get("playbook");
        Map<?, ?> manual = (Map<?, ?>) localCase.get("manualIntervention");
        assertEquals("LOCAL_RECORD_WAIT", playbook.get("code"));
        assertEquals(true, manual.get("required"));
    }

    @Test
    void selectsSpecificStatusRecoveryPlaybook() throws Exception {
        Map<String, Object> result = ReconEngine.reconcile(
                "local.csv", "csv",
                "merchant_order_no,amount,status\nSTATUS-1,120.00,PAYING\n",
                "channel.json", "json",
                "[{\"out_trade_no\":\"STATUS-1\",\"total_amount\":\"120.00\",\"trade_status\":\"SUCCESS\"}]");

        Map<?, ?> item = ((List<Map<String, Object>>) result.get("results")).getFirst();
        Map<?, ?> playbook = (Map<?, ?>) item.get("playbook");
        assertEquals("STATUS_SYNC_RECOVERY", playbook.get("code"));
        assertEquals("ASSISTED", playbook.get("automation"));
        assertTrue(((List<?>) playbook.get("forbiddenActions")).contains("重复发起扣款"));
    }

    @Test
    void createsCaseAndEnforcesStatusTransitions() throws Exception {
        Map<String, Object> result = ReconEngine.reconcile(
                "local.csv", "csv",
                "merchant_order_no,amount,status\nCASE-1,120.00,PAYING\n",
                "channel.json", "json",
                "[{\"out_trade_no\":\"CASE-1\",\"total_amount\":\"120.00\",\"trade_status\":\"SUCCESS\"}]");
        WorkflowStore.rememberCases(result);
        Map<String, Object> item = ((List<Map<String, Object>>) result.get("results")).getFirst();
        String caseId = String.valueOf(item.get("caseId"));

        assertEquals("READY", item.get("caseStatus"));
        assertTrue(((List<?>) ((Map<?, ?>) item.get("workflow")).get("allowedNextStatuses"))
                .contains("INVESTIGATING"));

        assertThrows(IllegalArgumentException.class,
                () -> WorkflowStore.transition(caseId, "AUTO_PROCESSING", "u1001", "跳过调查和审批"));
        WorkflowStore.transition(caseId, "INVESTIGATING", "u1001", "开始核查");
        WorkflowStore.transition(caseId, "PENDING_APPROVAL", "u1001", "核查完成，申请审批");
        assertEquals("PENDING_APPROVAL", WorkflowStore.getCase(caseId).get("caseStatus"));
    }

    @Test
    void completesLookupTaskAndRecordsAudit() throws Exception {
        Map<String, Object> result = ReconEngine.reconcile(
                "local.csv", "csv",
                "merchant_order_no,amount,status\nCASE-2,50.00,PAYING\n",
                "channel.json", "json",
                "[{\"out_trade_no\":\"CASE-2\",\"transaction_id\":\"CH-2\",\"total_amount\":\"50.00\",\"trade_status\":\"SUCCESS\"}]");
        WorkflowStore.rememberCases(result);
        Map<String, Object> item = ((List<Map<String, Object>>) result.get("results")).getFirst();
        String caseId = String.valueOf(item.get("caseId"));

        Map<String, Object> task = WorkflowStore.createLookupTask(caseId, "u1002");

        assertEquals("SUCCEEDED", task.get("status"));
        assertEquals("CH-2", task.get("channelTradeNo"));
        assertEquals("INVESTIGATING", task.get("caseStatus"));
        assertEquals("INVESTIGATING", WorkflowStore.getCase(caseId).get("caseStatus"));
        assertEquals(1, WorkflowStore.lookupTasks(caseId).size());
        assertTrue(WorkflowStore.audit(caseId).stream()
                .anyMatch(entry -> "LOOKUP_TASK_CREATED".equals(entry.get("action"))));
        assertTrue(WorkflowStore.audit(caseId).stream()
                .anyMatch(entry -> "LOOKUP_TASK_COMPLETED".equals(entry.get("action"))));
    }

    @Test
    void resendsBusinessEventIdempotentlyAfterApproval() throws Exception {
        Map<String, Object> result = ReconEngine.reconcile(
                "local.csv", "csv",
                "merchant_order_no,amount,status\nCASE-3,120.00,PAYING\n",
                "channel.json", "json",
                "[{\"out_trade_no\":\"CASE-3\",\"transaction_id\":\"CH-3\",\"total_amount\":\"120.00\",\"trade_status\":\"SUCCESS\"}]");
        WorkflowStore.rememberCases(result);
        Map<String, Object> item = ((List<Map<String, Object>>) result.get("results")).getFirst();
        String caseId = String.valueOf(item.get("caseId"));

        WorkflowStore.transition(caseId, "INVESTIGATING", "u1003", "完成查单");
        WorkflowStore.transition(caseId, "PENDING_APPROVAL", "u1003", "审批补发");
        Map<String, Object> first = WorkflowStore.resendEvent(
                caseId, "PAYMENT_SUCCEEDED", "u1003", "补发支付成功事件");
        Map<String, Object> replay = WorkflowStore.resendEvent(
                caseId, "PAYMENT_SUCCEEDED", "u1003", "重复提交");

        assertEquals("SENT", first.get("status"));
        assertTrue(String.valueOf(first.get("eventId")).length() > 10);
        assertEquals(first.get("eventId"), replay.get("eventId"));
        assertEquals("AUTO_PROCESSING", WorkflowStore.getCase(caseId).get("caseStatus"));
        assertEquals(1, WorkflowStore.audit(caseId).stream()
                .filter(entry -> "BUSINESS_EVENT_RESENT".equals(entry.get("action"))).count());
        assertEquals(1, WorkflowStore.audit(caseId).stream()
                .filter(entry -> "EVENT_IDEMPOTENT_REPLAY".equals(entry.get("action"))).count());
    }

    @Test
    void updatesManualInterventionAndClosesCase() throws Exception {
        Map<String, Object> result = ReconEngine.reconcile(
                "local.csv", "csv",
                "merchant_order_no,amount\nCASE-4,10.00\n",
                "channel.csv", "csv",
                "out_trade_no,total_amount\nOTHER,10.00\n");
        WorkflowStore.rememberCases(result);
        Map<String, Object> item = ((List<Map<String, Object>>) result.get("results")).getFirst();
        String caseId = String.valueOf(item.get("caseId"));

        Map<String, Object> view = WorkflowStore.updateManualIntervention(
                caseId, "u1004", "IN_PROGRESS", "已核对本地流水");
        assertEquals("IN_PROGRESS", ((Map<?, ?>) view.get("manualIntervention")).get("status"));
        assertEquals("INVESTIGATING", view.get("caseStatus"));

        view = WorkflowStore.updateManualIntervention(
                caseId, "u1004", "COMPLETED", "确认无需补发");
        assertEquals("COMPLETED", ((Map<?, ?>) view.get("manualIntervention")).get("status"));
        assertEquals("RESOLVED", view.get("caseStatus"));
        assertTrue(WorkflowStore.audit(caseId).stream()
                .anyMatch(entry -> "MANUAL_INTERVENTION".equals(entry.get("action"))));
    }
}
