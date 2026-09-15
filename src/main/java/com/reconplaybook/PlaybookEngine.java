package com.reconplaybook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PlaybookEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<PlaybookDefinition> DEFINITIONS = loadDefinitions();

    private PlaybookEngine() {
    }

    public static Decision decide(String discrepancyType, ReconEngine.Record local,
                                  ReconEngine.Record channel) {
        Map<String, String> context = contextFor(discrepancyType, local, channel);
        PlaybookDefinition definition = DEFINITIONS.stream()
                .filter(item -> discrepancyType.equals(item.discrepancyType))
                .filter(item -> matches(item.conditions, context))
                .findFirst()
                .orElseGet(() -> fallback(discrepancyType));

        Map<String, Object> playbook = new LinkedHashMap<>();
        playbook.put("code", definition.code);
        playbook.put("name", definition.name);
        playbook.put("discrepancyType", definition.discrepancyType);
        playbook.put("conditions", definition.conditions);
        playbook.put("matchedConditions", matchedConditions(definition.conditions, context));
        playbook.put("riskLevel", definition.riskLevel);
        playbook.put("automation", definition.automation);
        playbook.put("forbiddenActions", definition.forbiddenActions);
        playbook.put("processingPath", definition.processingPath);

        Map<String, Object> manual = new LinkedHashMap<>();
        boolean manualRequired = definition.manualIntervention != null
                && definition.manualIntervention.required;
        manual.put("required", manualRequired);
        manual.put("status", manualRequired ? "READY" : "NOT_REQUIRED");
        manual.put("trigger", definition.manualIntervention == null
                ? "" : definition.manualIntervention.trigger);
        manual.put("entryAction", definition.manualIntervention == null
                ? "" : definition.manualIntervention.entryAction);
        manual.put("operator", "");
        manual.put("note", "");
        manual.put("updatedAt", "");
        manual.put("history", new ArrayList<>());
        playbook.put("manualIntervention", manual);

        return new Decision(
                String.valueOf(playbook.get("code")),
                String.valueOf(playbook.get("riskLevel")),
                manualRequired,
                playbook
        );
    }

    public static List<Map<String, Object>> catalog() {
        return DEFINITIONS.stream().map(definition -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", definition.code);
            item.put("name", definition.name);
            item.put("discrepancyType", definition.discrepancyType);
            item.put("conditions", definition.conditions);
            item.put("riskLevel", definition.riskLevel);
            item.put("automation", definition.automation);
            item.put("forbiddenActions", definition.forbiddenActions);
            item.put("processingPath", definition.processingPath);
            item.put("manualIntervention", definition.manualIntervention);
            return item;
        }).toList();
    }

    private static Map<String, String> contextFor(String discrepancyType,
                                                   ReconEngine.Record local,
                                                   ReconEngine.Record channel) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("hasLocal", Boolean.toString(local != null));
        context.put("hasChannel", Boolean.toString(channel != null));
        context.put("localStatus", local == null ? "" : local.status());
        context.put("channelStatus", channel == null ? "" : channel.status());
        context.put("feeEqual", Boolean.toString(local != null && channel != null
                && local.fee().compareTo(channel.fee()) == 0));
        context.put("duplicate", Boolean.toString("DUPLICATE_CHANNEL_RECORD".equals(discrepancyType)));
        return context;
    }

    private static boolean matches(Map<String, String> conditions, Map<String, String> context) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        return conditions.entrySet().stream()
                .allMatch(condition -> Objects.equals(condition.getValue(), context.get(condition.getKey())));
    }

    private static List<String> matchedConditions(Map<String, String> conditions,
                                                   Map<String, String> context) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of("命中该差异类型的默认条件");
        }
        return conditions.entrySet().stream()
                .map(condition -> condition.getKey() + "=" + context.get(condition.getKey()))
                .toList();
    }

    private static PlaybookDefinition fallback(String discrepancyType) {
        PlaybookDefinition definition = new PlaybookDefinition();
        definition.code = "FALLBACK_" + discrepancyType;
        definition.name = "默认差错处理剧本";
        definition.discrepancyType = discrepancyType;
        definition.riskLevel = "HIGH";
        definition.automation = "MANUAL";
        definition.forbiddenActions = List.of("执行不可逆资金动作");
        definition.processingPath = List.of(new ProcessingStep(
                1, "查看原始账单和业务日志", "人工", "MANUAL"));
        definition.manualIntervention = new ManualIntervention(
                true, "未找到专用剧本", "创建人工处理任务");
        return definition;
    }

    private static List<PlaybookDefinition> loadDefinitions() {
        try (InputStream input = PlaybookEngine.class.getResourceAsStream("/playbooks.json")) {
            if (input == null) {
                throw new IllegalStateException("playbooks.json not found");
            }
            return MAPPER.readValue(input, new TypeReference<>() {
            });
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("无法加载差错剧本配置", exception);
        }
    }

    public record Decision(String code, String riskLevel, boolean manualRequired,
                           Map<String, Object> playbook) {
    }

    public static final class PlaybookDefinition {
        public String code;
        public String name;
        public String discrepancyType;
        public Map<String, String> conditions = new LinkedHashMap<>();
        public String riskLevel = "HIGH";
        public String automation = "MANUAL";
        public List<String> forbiddenActions = new ArrayList<>();
        public List<ProcessingStep> processingPath = new ArrayList<>();
        public ManualIntervention manualIntervention = new ManualIntervention(
                false, "", "");
    }

    public record ProcessingStep(int order, String action, String owner, String mode) {
    }

    public record ManualIntervention(boolean required, String trigger, String entryAction) {
    }
}
