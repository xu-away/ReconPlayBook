package com.reconplaybook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WorkflowStore {
    private static final Map<String, Map<String, Object>> CASES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> LOOKUP_TASKS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> EVENTS = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> AUDIT_LOG = new CopyOnWriteArrayList<>();

    private WorkflowStore() {
    }

    public static void rememberCases(Map<String, Object> reconciliation) {
        Object resultValue = reconciliation.get("results");
        if (!(resultValue instanceof List<?> results)) {
            return;
        }
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> rawCase)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> caseMap = (Map<String, Object>) rawCase;
            String caseId = String.valueOf(caseMap.get("caseId"));
            String status = initialStatus(caseMap);
            caseMap.put("caseStatus", status);
            Map<String, Object> workflow = new LinkedHashMap<>();
            workflow.put("status", status);
            workflow.put("allowedNextStatuses", allowedNextStatuses(status));
            caseMap.put("workflow", workflow);
            CASES.put(caseId, caseMap);
            audit(caseId, "CASE_CREATED", "SYSTEM",
                    "差错单创建，初始状态：" + status, Map.of(
                            "discrepancyType", caseMap.get("discrepancyType"),
                            "risk", caseMap.get("risk")
                    ));
        }
    }

    public static Map<String, Object> getCase(String caseId) {
        return CASES.get(caseId);
    }

    public static Map<String, Object> transition(String caseId, String targetStatus,
                                                  String actor, String note) {
        Map<String, Object> caseMap = requireCase(caseId);
        String currentStatus = String.valueOf(caseMap.get("caseStatus"));
        String target = normalizeStatus(targetStatus);
        if (!allowedNextStatuses(currentStatus).contains(target)) {
            throw new IllegalArgumentException("不允许从 " + currentStatus + " 流转到 " + target);
        }
        caseMap.put("caseStatus", target);
        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) caseMap.get("workflow");
        workflow.put("status", target);
        workflow.put("allowedNextStatuses", allowedNextStatuses(target));
        audit(caseId, "CASE_STATUS_CHANGED", actor,
                currentStatus + " -> " + target + (note.isBlank() ? "" : "：" + note),
                Map.of("from", currentStatus, "to", target));
        return caseView(caseMap);
    }

    public static Map<String, Object> updateManualIntervention(String caseId,
                                                                 String operator,
                                                                 String manualStatus,
                                                                 String note) {
        Map<String, Object> caseMap = requireCase(caseId);
        String desiredCaseStatus = switch (manualStatus) {
            case "IN_PROGRESS" -> "INVESTIGATING";
            case "WAITING_EXTERNAL" -> "WAITING_EXTERNAL";
            case "COMPLETED" -> "RESOLVED";
            case "REJECTED" -> "REJECTED";
            default -> throw new IllegalArgumentException(
                    "status 必须是 IN_PROGRESS、WAITING_EXTERNAL、COMPLETED 或 REJECTED");
        };
        String currentStatus = String.valueOf(caseMap.get("caseStatus"));
        if (!currentStatus.equals(desiredCaseStatus)) {
            transition(caseId, desiredCaseStatus, operator, note);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> manual = (Map<String, Object>) caseMap.get("manualIntervention");
        String now = Instant.now().toString();
        manual.put("status", manualStatus);
        manual.put("operator", operator);
        manual.put("note", note);
        manual.put("updatedAt", now);
        Object historyValue = manual.get("history");
        if (historyValue instanceof List<?> history) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> writableHistory = (List<Map<String, Object>>) history;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", manualStatus);
            entry.put("operator", operator);
            entry.put("note", note);
            entry.put("updatedAt", now);
            writableHistory.add(entry);
        }
        audit(caseId, "MANUAL_INTERVENTION", operator,
                "人工处理状态：" + manualStatus + (note.isBlank() ? "" : "，备注：" + note),
                Map.of("manualStatus", manualStatus));
        return caseView(caseMap);
    }

    public static Map<String, Object> createLookupTask(String caseId, String operator) {
        Map<String, Object> caseMap = requireCase(caseId);
        String taskId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", taskId);
        task.put("caseId", caseId);
        task.put("taskType", "ACTIVE_ORDER_LOOKUP");
        task.put("status", "RUNNING");
        task.put("operator", operator);
        task.put("createdAt", now);
        task.put("adapter", "LOCAL_CHANNEL_ADAPTER");
        task.put("queryKey", orderNo(caseMap));
        LOOKUP_TASKS.put(taskId, task);
        audit(caseId, "LOOKUP_TASK_CREATED", operator,
                "创建主动查单任务：" + taskId, Map.of("taskId", taskId));

        Map<String, Object> channel = mapValue(caseMap.get("channel"));
        if (channel == null) {
            task.put("status", "NOT_FOUND");
            task.put("message", "当前账单中没有渠道记录，真实环境应调用渠道查询接口。");
        } else {
            task.put("status", "SUCCEEDED");
            task.put("message", "本地渠道适配器已返回账单中的渠道记录。");
            task.put("channelTradeNo", channel.get("tradeNo"));
            task.put("channelStatus", channel.get("status"));
            task.put("channelAmount", channel.get("amount"));
        }
        task.put("completedAt", Instant.now().toString());
        audit(caseId, "LOOKUP_TASK_COMPLETED", "SYSTEM",
                "主动查单任务完成：" + task.get("status"),
                Map.of("taskId", taskId, "status", task.get("status")));

        String currentStatus = String.valueOf(caseMap.get("caseStatus"));
        if ("READY".equals(currentStatus) || "WAITING_EXTERNAL".equals(currentStatus)) {
            transition(caseId, "INVESTIGATING", operator, "主动查单已完成，进入调查");
        }
        task.put("caseStatus", caseMap.get("caseStatus"));
        task.put("workflow", caseMap.get("workflow"));
        return task;
    }

    public static List<Map<String, Object>> lookupTasks(String caseId) {
        return LOOKUP_TASKS.values().stream()
                .filter(task -> caseId == null || caseId.isBlank()
                        || caseId.equals(task.get("caseId")))
                .toList();
    }

    public static Map<String, Object> resendEvent(String caseId, String eventType,
                                                   String operator, String note) {
        Map<String, Object> caseMap = requireCase(caseId);
        String normalizedEventType = eventType == null ? "" : eventType.trim().toUpperCase();
        if (!List.of("PAYMENT_SUCCEEDED", "REFUND_SUCCEEDED").contains(normalizedEventType)) {
            throw new IllegalArgumentException("eventType 只支持 PAYMENT_SUCCEEDED 或 REFUND_SUCCEEDED");
        }
        if (!"STATUS_MISMATCH".equals(caseMap.get("discrepancyType"))) {
            throw new IllegalArgumentException("只有状态不一致案例允许通过当前原型补发业务事件");
        }
        String status = String.valueOf(caseMap.get("caseStatus"));
        if (!"PENDING_APPROVAL".equals(status) && !"AUTO_PROCESSING".equals(status)) {
            throw new IllegalArgumentException("补发事件前，差错单必须处于 PENDING_APPROVAL 状态");
        }

        String idempotencyKey = caseId + ":" + normalizedEventType;
        Map<String, Object> existing = EVENTS.get(idempotencyKey);
        if (existing != null) {
            audit(caseId, "EVENT_IDEMPOTENT_REPLAY", operator,
                    "重复请求被幂等拦截：" + idempotencyKey,
                    Map.of("eventId", existing.get("eventId")));
            return existing;
        }

        if ("PENDING_APPROVAL".equals(status)) {
            transition(caseId, "AUTO_PROCESSING", operator,
                    "人工审批通过，开始补发事件");
        }
        Map<String, Object> local = mapValue(caseMap.get("local"));
        Map<String, Object> channel = mapValue(caseMap.get("channel"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("caseId", caseId);
        event.put("eventType", normalizedEventType);
        event.put("idempotencyKey", idempotencyKey);
        event.put("status", "SENT");
        event.put("transport", "LOCAL_OUTBOX");
        event.put("operator", operator);
        event.put("sentAt", Instant.now().toString());
        event.put("payload", Map.of(
                "orderNo", orderNo(caseMap),
                "tradeNo", channel == null ? "" : String.valueOf(channel.get("tradeNo")),
                "amount", local == null ? "" : String.valueOf(local.get("amount")),
                "note", note == null ? "" : note
        ));
        EVENTS.put(idempotencyKey, event);
        audit(caseId, "BUSINESS_EVENT_RESENT", operator,
                "补发业务事件：" + normalizedEventType,
                Map.of("eventId", event.get("eventId"), "idempotencyKey", idempotencyKey));
        return event;
    }

    public static List<Map<String, Object>> audit(String caseId) {
        return AUDIT_LOG.stream()
                .filter(item -> caseId == null || caseId.isBlank()
                        || caseId.equals(item.get("caseId")))
                .toList();
    }

    public static Map<String, Object> workbench() {
        List<Map<String, Object>> pending = CASES.values().stream()
                .filter(WorkflowStore::requiresManual)
                .filter(item -> !List.of("RESOLVED", "REJECTED")
                        .contains(String.valueOf(item.get("caseStatus"))))
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", pending.size());
        response.put("cases", pending);
        response.put("statusCounts", pending.stream().collect(java.util.stream.Collectors.groupingBy(
                item -> String.valueOf(item.get("caseStatus")),
                LinkedHashMap::new,
                java.util.stream.Collectors.counting())));
        return response;
    }

    private static boolean requiresManual(Map<String, Object> caseMap) {
        Map<String, Object> manual = mapValue(caseMap.get("manualIntervention"));
        return manual != null && Boolean.TRUE.equals(manual.get("required"));
    }

    private static String initialStatus(Map<String, Object> caseMap) {
        if ("MATCHED".equals(caseMap.get("discrepancyType"))) {
            return "RESOLVED";
        }
        Map<String, Object> playbook = mapValue(caseMap.get("playbook"));
        if (playbook != null && "WAIT".equals(playbook.get("automation"))) {
            return "WAITING_EXTERNAL";
        }
        Map<String, Object> manual = mapValue(caseMap.get("manualIntervention"));
        if (manual != null && Boolean.TRUE.equals(manual.get("required"))) {
            return "READY";
        }
        return "RESOLVED";
    }

    private static List<String> allowedNextStatuses(String current) {
        return switch (current) {
            case "READY" -> List.of("INVESTIGATING", "WAITING_EXTERNAL", "PENDING_APPROVAL",
                    "RESOLVED", "REJECTED");
            case "INVESTIGATING" -> List.of("WAITING_EXTERNAL", "PENDING_APPROVAL",
                    "AUTO_PROCESSING", "RESOLVED", "REJECTED");
            case "WAITING_EXTERNAL" -> List.of("INVESTIGATING", "PENDING_APPROVAL",
                    "RESOLVED", "REJECTED");
            case "PENDING_APPROVAL" -> List.of("INVESTIGATING", "AUTO_PROCESSING",
                    "RESOLVED", "REJECTED");
            case "AUTO_PROCESSING" -> List.of("INVESTIGATING", "RESOLVED", "REJECTED");
            case "RESOLVED", "REJECTED" -> List.of("INVESTIGATING");
            default -> List.of();
        };
    }

    private static String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!List.of("READY", "INVESTIGATING", "WAITING_EXTERNAL", "PENDING_APPROVAL",
                "AUTO_PROCESSING", "RESOLVED", "REJECTED").contains(normalized)) {
            throw new IllegalArgumentException("不支持的差错单状态：" + status);
        }
        return normalized;
    }

    private static Map<String, Object> requireCase(String caseId) {
        Map<String, Object> caseMap = CASES.get(caseId);
        if (caseMap == null) {
            throw new IllegalArgumentException("案例不存在或服务已重启");
        }
        return caseMap;
    }

    private static Map<String, Object> caseView(Map<String, Object> caseMap) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("caseId", caseMap.get("caseId"));
        view.put("caseStatus", caseMap.get("caseStatus"));
        view.put("workflow", caseMap.get("workflow"));
        view.put("manualIntervention", caseMap.get("manualIntervention"));
        return view;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static String orderNo(Map<String, Object> caseMap) {
        Map<String, Object> local = mapValue(caseMap.get("local"));
        Map<String, Object> channel = mapValue(caseMap.get("channel"));
        Object orderNo = local == null ? null : local.get("orderNo");
        if (orderNo == null && channel != null) {
            orderNo = channel.get("orderNo");
        }
        return orderNo == null ? "" : String.valueOf(orderNo);
    }

    private static void audit(String caseId, String action, String actor, String message,
                              Map<String, Object> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("auditId", UUID.randomUUID().toString());
        entry.put("caseId", caseId);
        entry.put("action", action);
        entry.put("actor", actor == null || actor.isBlank() ? "SYSTEM" : actor);
        entry.put("message", message);
        entry.put("details", details == null ? Map.of() : details);
        entry.put("createdAt", Instant.now().toString());
        AUDIT_LOG.add(entry);
    }

    public static void clearForTests() {
        CASES.clear();
        LOOKUP_TASKS.clear();
        EVENTS.clear();
        AUDIT_LOG.clear();
    }
}
