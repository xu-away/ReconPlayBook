package com.reconplaybook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReconApplication {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReconApplication() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getProperty("port", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ReconApplication::handleStatic);
        server.createContext("/api/health", ReconApplication::handleHealth);
        server.createContext("/api/demo", ReconApplication::handleDemo);
        server.createContext("/api/playbooks", ReconApplication::handlePlaybooks);
        server.createContext("/api/workbench", ReconApplication::handleWorkbench);
        server.createContext("/api/reconcile", ReconApplication::handleReconcile);
        server.createContext("/api/manual-interventions", ReconApplication::handleManualIntervention);
        server.createContext("/api/case-status", ReconApplication::handleCaseStatus);
        server.createContext("/api/lookup-tasks", ReconApplication::handleLookupTasks);
        server.createContext("/api/events/resend", ReconApplication::handleEventResend);
        server.createContext("/api/audit", ReconApplication::handleAudit);
        server.setExecutor(null);
        server.start();
        System.out.printf("ReconPlaybook V0.4 running at http://localhost:%d%n", port);
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        send(exchange, 200, Map.of("status", "ok", "version", "0.4.0"));
    }

    private static void handlePlaybooks(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        send(exchange, 200, Map.of("count", PlaybookEngine.catalog().size(),
                "playbooks", PlaybookEngine.catalog()));
    }

    private static void handleDemo(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        Map<String, String> demo = new LinkedHashMap<>();
        demo.put("localName", "local-demo.csv");
        demo.put("localFormat", "csv");
        demo.put("localContent", DemoData.LOCAL_CSV);
        demo.put("channelName", "channel-demo.json");
        demo.put("channelFormat", "json");
        demo.put("channelContent", DemoData.CHANNEL_JSON);
        send(exchange, 200, demo);
    }

    private static void handleReconcile(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            Map<String, Object> result = ReconEngine.reconcile(
                    text(request, "localName", "local.csv"),
                    text(request, "localFormat", "csv"),
                    text(request, "localContent", ""),
                    text(request, "channelName", "channel.csv"),
                    text(request, "channelFormat", "csv"),
                    text(request, "channelContent", "")
            );
            WorkflowStore.rememberCases(result);
            send(exchange, 200, result);
        } catch (IllegalArgumentException | IOException exception) {
            send(exchange, 400, Map.of("error", exception.getMessage() == null
                    ? "Invalid reconciliation request" : exception.getMessage()));
        }
    }

    private static void handleWorkbench(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        send(exchange, 200, WorkflowStore.workbench());
    }

    private static void handleManualIntervention(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            String caseId = text(request, "caseId", "");
            String operator = text(request, "operator", "").trim();
            String status = text(request, "status", "IN_PROGRESS").trim().toUpperCase();
            String note = text(request, "note", "").trim();
            if (caseId.isBlank()) {
                send(exchange, 400, Map.of("error", "caseId 不能为空"));
                return;
            }
            if (operator.isBlank()) {
                send(exchange, 400, Map.of("error", "operator 不能为空"));
                return;
            }
            if (!List.of("IN_PROGRESS", "WAITING_EXTERNAL", "COMPLETED", "REJECTED")
                    .contains(status)) {
                send(exchange, 400, Map.of("error", "status 必须是 IN_PROGRESS、WAITING_EXTERNAL、COMPLETED 或 REJECTED"));
                return;
            }

            Map<String, Object> caseMap = WorkflowStore.getCase(caseId);
            if (caseMap == null) {
                send(exchange, 404, Map.of("error", "案例不存在或服务已重启"));
                return;
            }
            Map<String, Object> manual = mapValue(caseMap.get("manualIntervention"));
            boolean required = manual != null && Boolean.TRUE.equals(manual.get("required"));
            if (!required && !"COMPLETED".equals(status)) {
                send(exchange, 400, Map.of("error", "该案例无需人工介入"));
                return;
            }
            Map<String, Object> response = WorkflowStore.updateManualIntervention(
                    caseId, operator, status, note);
            response.put("message", "人工介入状态已更新");
            send(exchange, 200, response);
        } catch (IllegalArgumentException | IOException exception) {
            send(exchange, 400, Map.of("error", exception.getMessage() == null
                    ? "Invalid manual intervention request" : exception.getMessage()));
        }
    }

    private static void handleCaseStatus(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            String caseId = text(request, "caseId", "");
            String status = text(request, "status", "");
            String actor = text(request, "actor", "operator");
            String note = text(request, "note", "");
            Map<String, Object> response = WorkflowStore.transition(caseId, status, actor, note);
            response.put("message", "差错单状态已更新");
            send(exchange, 200, response);
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, Map.of("error", exception.getMessage()));
        }
    }

    private static void handleLookupTasks(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String caseId = query(exchange, "caseId");
            send(exchange, 200, Map.of("tasks", WorkflowStore.lookupTasks(caseId)));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            String caseId = text(request, "caseId", "");
            String operator = text(request, "operator", "operator");
            send(exchange, 200, WorkflowStore.createLookupTask(caseId, operator));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, Map.of("error", exception.getMessage()));
        }
    }

    private static void handleEventResend(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        try {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            String caseId = text(request, "caseId", "");
            String eventType = text(request, "eventType", "PAYMENT_SUCCEEDED");
            String operator = text(request, "operator", "operator");
            String note = text(request, "note", "");
            Map<String, Object> event = WorkflowStore.resendEvent(
                    caseId, eventType, operator, note);
            send(exchange, 200, Map.of(
                    "event", event,
                    "caseId", caseId,
                    "caseStatus", WorkflowStore.getCase(caseId).get("caseStatus"),
                    "workflow", WorkflowStore.getCase(caseId).get("workflow"),
                    "message", "业务事件已补发（本地出站模拟）"
            ));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, Map.of("error", exception.getMessage()));
        }
    }

    private static void handleAudit(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        String caseId = query(exchange, "caseId");
        send(exchange, 200, Map.of("caseId", caseId == null ? "" : caseId,
                "entries", WorkflowStore.audit(caseId)));
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] values = pair.split("=", 2);
            if (values.length == 2 && name.equals(values[0])) {
                return values[1];
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(exchange, 404, Map.of("error", "Not found"));
            return;
        }
        String resource = "/static" + path;
        try (InputStream input = ReconApplication.class.getResourceAsStream(resource)) {
            if (input == null) {
                send(exchange, 404, Map.of("error", "Not found"));
                return;
            }
            byte[] content = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(path));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(content);
            }
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] content = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static final class DemoData {
        private static final String LOCAL_CSV = """
                merchant_order_no,amount,currency,status,fee,transaction_time
                L1001,100.00,CNY,SUCCESS,0.60,2026-09-12 09:12:00
                L1002,59.99,CNY,SUCCESS,0.36,2026-09-12 09:18:00
                L1003,88.00,CNY,SUCCESS,0.53,2026-09-12 09:23:00
                L1004,120.00,CNY,PAYING,0.72,2026-09-12 09:30:00
                L1005,45.00,CNY,SUCCESS,0.27,2026-09-10 10:00:00
                L1006,30.00,CNY,SUCCESS,0.18,2026-09-12 10:05:00
                """;

        private static final String CHANNEL_JSON = """
                [
                  {"out_trade_no":"L1001","transaction_id":"CH-1001","total_amount":"100.00","currency_code":"CNY","trade_status":"paid","service_fee":"0.60","paid_at":"2026-09-12T09:12:14+08:00"},
                  {"out_trade_no":"L1002","transaction_id":"CH-1002","total_amount":"59.00","currency_code":"CNY","trade_status":"SUCCESS","service_fee":"0.36","paid_at":"2026-09-12T09:18:11+08:00"},
                  {"out_trade_no":"L1004","transaction_id":"CH-1004","total_amount":"120.00","currency_code":"CNY","trade_status":"SUCCESS","service_fee":"0.72","paid_at":"2026-09-12T09:30:09+08:00"},
                  {"out_trade_no":"L1005","transaction_id":"CH-1005","total_amount":"45.00","currency_code":"CNY","trade_status":"SUCCESS","service_fee":"0.27","paid_at":"2026-09-13T10:00:00+08:00"},
                  {"out_trade_no":"L1006","transaction_id":"CH-1006-A","total_amount":"30.00","currency_code":"CNY","trade_status":"SUCCESS","service_fee":"0.18","paid_at":"2026-09-12T10:05:03+08:00"},
                  {"out_trade_no":"L1006","transaction_id":"CH-1006-B","total_amount":"30.00","currency_code":"CNY","trade_status":"SUCCESS","service_fee":"0.18","paid_at":"2026-09-12T10:05:06+08:00"},
                  {"out_trade_no":"CHANNEL-ONLY-1","transaction_id":"CH-EXTRA-1","total_amount":"16.80","currency_code":"CNY","trade_status":"SUCCESS","service_fee":"0.10","paid_at":"2026-09-12T11:20:00+08:00"}
                ]
                """;
    }
}
