package com.reconplaybook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;

public final class ReconEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration MATCH_WINDOW = Duration.ofHours(24);
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");

    private static final Map<String, List<String>> ALIASES = Map.of(
            "orderNo", List.of("orderNo", "merchantOrderNo", "merchant_order_no", "out_trade_no", "order_id", "订单号"),
            "tradeNo", List.of("tradeNo", "channelTradeNo", "channel_trade_no", "trade_no", "transaction_id", "流水号"),
            "amount", List.of("amount", "payAmount", "pay_amount", "total_amount", "金额"),
            "currency", List.of("currency", "currency_code", "币种"),
            "status", List.of("status", "trade_status", "payment_status", "交易状态"),
            "fee", List.of("fee", "channel_fee", "service_fee", "手续费"),
            "time", List.of("time", "transactionTime", "transaction_time", "paid_at", "success_time", "交易时间"),
            "refundAmount", List.of("refundAmount", "refund_amount", "refunded_amount", "退款金额")
    );

    private ReconEngine() {
    }

    public static Map<String, Object> reconcile(String localName, String localFormat, String localContent,
                                                  String channelName, String channelFormat, String channelContent)
            throws IOException {
        List<Record> local = parse(localContent, localFormat, "LOCAL");
        List<Record> channel = parse(channelContent, channelFormat, "CHANNEL");
        List<Map<String, Object>> results = new ArrayList<>();
        boolean[] used = new boolean[channel.size()];

        for (Record localRecord : local) {
            List<Integer> candidates = candidateIndexes(localRecord, channel, used);
            if (candidates.isEmpty()) {
                results.add(result("UNMATCHED_LOCAL", localRecord, null,
                        "渠道账单中没有找到相同订单号的流水。",
                        "等待下一批渠道账单；超过延迟窗口后转人工核查。"));
                continue;
            }

            int bestIndex = chooseBest(localRecord, channel, candidates);
            Record channelRecord = channel.get(bestIndex);
            used[bestIndex] = true;

            String discrepancyType = classify(localRecord, channelRecord);
            results.add(result(discrepancyType, localRecord, channelRecord,
                    reasonFor(discrepancyType),
                    actionFor(discrepancyType)));
        }

        for (int i = 0; i < channel.size(); i++) {
            if (used[i]) {
                continue;
            }
            Record channelRecord = channel.get(i);
            boolean duplicate = local.stream().anyMatch(localRecord ->
                    !localRecord.orderNo().isBlank()
                            && localRecord.orderNo().equals(channelRecord.orderNo()));
            String type = duplicate ? "DUPLICATE_CHANNEL_RECORD" : "UNMATCHED_CHANNEL";
            results.add(result(type, null, channelRecord,
                    reasonFor(type), actionFor(type)));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("localFile", localName);
        response.put("channelFile", channelName);
        response.put("localFormat", localFormat.toUpperCase(Locale.ROOT));
        response.put("channelFormat", channelFormat.toUpperCase(Locale.ROOT));
        response.put("matchWindowHours", MATCH_WINDOW.toHours());
        response.put("stats", stats(results));
        response.put("results", results);
        return response;
    }

    public static List<Record> parse(String content, String format, String source) throws IOException {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalizedFormat = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if ("json".equals(normalizedFormat) || content.trim().startsWith("[")
                || content.trim().startsWith("{")) {
            JsonNode root = MAPPER.readTree(content);
            JsonNode rows = root.isArray() ? root : firstExisting(root, "records", "data", "items");
            if (rows == null || !rows.isArray()) {
                throw new IllegalArgumentException("JSON 必须是数组，或包含 records/data/items 数组。");
            }
            List<Record> records = new ArrayList<>();
            int index = 1;
            for (JsonNode row : rows) {
                records.add(normalize(MAPPER.convertValue(row, new TypeReference<Map<String, Object>>() {
                }), source, index++));
            }
            return records;
        }
        return parseCsv(content, source);
    }

    private static List<Record> parseCsv(String content, String source) {
        List<String> lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
                .filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        List<String> headers = splitCsvLine(lines.get(0));
        List<Record> records = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> values = splitCsvLine(lines.get(i));
            Map<String, Object> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.size(); j++) {
                row.put(headers.get(j), j < values.size() ? values.get(j) : "");
            }
            records.add(normalize(row, source, i));
        }
        return records;
    }

    private static List<String> splitCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    private static Record normalize(Map<String, Object> row, String source, int rowNumber) {
        String orderNo = value(row, "orderNo");
        String tradeNo = value(row, "tradeNo");
        BigDecimal amount = decimal(value(row, "amount"));
        BigDecimal fee = decimal(value(row, "fee"));
        BigDecimal refundAmount = decimal(value(row, "refundAmount"));
        String currency = value(row, "currency").isBlank() ? "CNY" : value(row, "currency").toUpperCase(Locale.ROOT);
        String status = normalizeStatus(value(row, "status"));
        String time = value(row, "time");
        Instant parsedTime = parseTime(time);
        return new Record(source, rowNumber, orderNo, tradeNo, amount, currency, status, fee, time,
                parsedTime, refundAmount, row);
    }

    private static String value(Map<String, Object> row, String field) {
        for (String alias : ALIASES.getOrDefault(field, List.of(field))) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().trim().equalsIgnoreCase(alias)) {
                    return entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
                }
            }
        }
        return "";
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO.setScale(2);
        }
        try {
            return new BigDecimal(value.replace(",", "")).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO.setScale(2);
        }
    }

    private static String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PAID", "SUCCESS", "SUCCEEDED", "COMPLETED", "CAPTURED" -> "SUCCESS";
            case "PAYING", "PROCESSING", "PENDING", "IN_PROGRESS" -> "PROCESSING";
            case "FAILED", "FAIL", "CLOSED", "CANCELLED" -> "FAILED";
            case "REFUNDED", "REFUND_SUCCESS" -> "REFUNDED";
            default -> normalized.isBlank() ? "UNKNOWN" : normalized;
        };
    }

    private static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME)) {
            try {
                return LocalDateTime.parse(value, formatter).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static List<Integer> candidateIndexes(Record local, List<Record> channel, boolean[] used) {
        List<Integer> exactOrder = new ArrayList<>();
        for (int i = 0; i < channel.size(); i++) {
            Record candidate = channel.get(i);
            if (!used[i] && sameOrder(local, candidate)) {
                exactOrder.add(i);
            }
        }
        return exactOrder;
    }

    private static boolean sameOrder(Record local, Record channel) {
        return !local.orderNo().isBlank()
                && local.orderNo().equalsIgnoreCase(channel.orderNo())
                && (local.currency().isBlank() || channel.currency().isBlank()
                || local.currency().equalsIgnoreCase(channel.currency()));
    }

    private static int chooseBest(Record local, List<Record> channel, List<Integer> candidates) {
        return candidates.stream().min(Comparator
                .comparing((Integer index) -> amountDifference(local, channel.get(index)))
                .thenComparing(index -> timeDifference(local, channel.get(index))))
                .orElse(candidates.getFirst());
    }

    private static BigDecimal amountDifference(Record local, Record channel) {
        return local.amount().subtract(channel.amount()).abs();
    }

    private static long timeDifference(Record local, Record channel) {
        if (local.parsedTime() == null || channel.parsedTime() == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(Duration.between(local.parsedTime(), channel.parsedTime()).toMinutes());
    }

    private static String classify(Record local, Record channel) {
        if (amountDifference(local, channel).compareTo(AMOUNT_TOLERANCE) > 0) {
            return "AMOUNT_MISMATCH";
        }
        if (!local.status().equals(channel.status())) {
            return "STATUS_MISMATCH";
        }
        if (local.fee().subtract(channel.fee()).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
            return "FEE_MISMATCH";
        }
        if (local.parsedTime() != null && channel.parsedTime() != null
                && timeDifference(local, channel) > MATCH_WINDOW.toMinutes()) {
            return "LATE_ARRIVAL";
        }
        return "MATCHED";
    }

    private static Map<String, Object> result(String type, Record local, Record channel,
                                               String reason, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discrepancyType", type);
        PlaybookEngine.Decision decision = PlaybookEngine.decide(type, local, channel);
        result.put("caseId", UUID.randomUUID().toString());
        result.put("risk", decision.riskLevel());
        result.put("reason", reason);
        result.put("recommendedAction", action);
        result.put("playbook", decision.playbook());
        result.put("manualIntervention", decision.playbook().get("manualIntervention"));
        result.put("diagnosis", diagnosis(type, local, channel));
        result.put("local", local == null ? null : local.toMap());
        result.put("channel", channel == null ? null : channel.toMap());
        result.put("evidence", evidence(local, channel));
        return result;
    }

    private static Map<String, Object> diagnosis(String type, Record local, Record channel) {
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put("code", type);
        diagnosis.put("primaryCause", primaryCause(type, local, channel));
        diagnosis.put("confidence", confidenceFor(type, local, channel));
        diagnosis.put("impact", impactFor(type));
        diagnosis.put("evidenceFacts", evidenceFacts(type, local, channel));
        diagnosis.put("nextChecks", nextChecksFor(type));
        diagnosis.put("blockedActions", blockedActionsFor(type));
        return diagnosis;
    }

    private static String primaryCause(String type, Record local, Record channel) {
        return switch (type) {
            case "MATCHED" -> "未发现业务差异，当前两边流水可以视为同一笔交易。";
            case "AMOUNT_MISMATCH" -> {
                if (local != null && channel != null
                        && local.fee().compareTo(channel.fee()) == 0) {
                    yield "更可能是支付金额口径、折扣或部分退款造成的金额差异，手续费字段目前一致。";
                }
                yield "更可能是金额口径、手续费扣除方式或支付调整造成的差异。";
            }
            case "STATUS_MISMATCH" -> {
                if (local != null && channel != null
                        && "SUCCESS".equals(channel.status())
                        && "PROCESSING".equals(local.status())) {
                    yield "更可能是渠道已完成扣款，但本地回调或状态同步尚未完成。";
                }
                yield "更可能是两边状态映射不一致，或支付状态同步存在延迟。";
            }
            case "FEE_MISMATCH" -> "更可能是渠道手续费规则、结算口径或费率版本不一致。";
            case "LATE_ARRIVAL" -> "更可能是渠道账单延迟、业务日期不同或两边时区口径不一致。";
            case "DUPLICATE_CHANNEL_RECORD" -> "更可能是渠道重复出账、重复导入，或渠道流水唯一性约束未生效。";
            case "UNMATCHED_LOCAL" -> "可能是渠道账单延迟、账单范围遗漏、订单号映射不一致，或本地记录尚未进入渠道账单。";
            case "UNMATCHED_CHANNEL" -> "可能是本地流水缺失、本地导出范围不完整，或渠道存在本地未落库的交易。";
            default -> "当前证据不足，需要结合原始报文和业务日志继续核查。";
        };
    }

    private static String confidenceFor(String type, Record local, Record channel) {
        return switch (type) {
            case "MATCHED", "AMOUNT_MISMATCH", "STATUS_MISMATCH", "FEE_MISMATCH",
                 "DUPLICATE_CHANNEL_RECORD" -> "HIGH";
            case "LATE_ARRIVAL", "UNMATCHED_LOCAL", "UNMATCHED_CHANNEL" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private static String impactFor(String type) {
        return switch (type) {
            case "MATCHED" -> "无需处置，保留匹配证据后归档。";
            case "LATE_ARRIVAL" -> "可能只是账单时序问题，暂不应据此执行资金修复。";
            case "FEE_MISMATCH" -> "可能造成结算差异或财务核算偏差。";
            case "AMOUNT_MISMATCH" -> "可能造成订单金额、结算金额或用户实付金额不一致。";
            case "STATUS_MISMATCH" -> "可能造成重复扣款、错误关单或下游业务状态错误。";
            case "DUPLICATE_CHANNEL_RECORD" -> "可能造成重复入账或重复计算渠道收入。";
            case "UNMATCHED_LOCAL", "UNMATCHED_CHANNEL" -> "可能存在漏记账、漏入账或错误判断交易结果。";
            default -> "需要人工确认影响范围。";
        };
    }

    private static List<String> evidenceFacts(String type, Record local, Record channel) {
        List<String> facts = new ArrayList<>();
        if (local != null) {
            facts.add("本地记录存在，行号 " + local.rowNumber());
            facts.add("本地状态 " + local.status() + "，金额 " + local.amount());
        }
        if (channel != null) {
            facts.add("渠道记录存在，行号 " + channel.rowNumber());
            facts.add("渠道状态 " + channel.status() + "，金额 " + channel.amount());
        }
        if (local != null && channel != null) {
            facts.add("金额差 " + amountDifference(local, channel));
            facts.add("时间差 " + timeDifference(local, channel) + " 分钟");
        }
        if ("UNMATCHED_LOCAL".equals(type)) {
            facts.add("当前渠道账单未找到相同订单号的可用记录");
        }
        if ("UNMATCHED_CHANNEL".equals(type)) {
            facts.add("当前本地流水未找到相同订单号的可用记录");
        }
        if ("DUPLICATE_CHANNEL_RECORD".equals(type)) {
            facts.add("相同订单号的渠道记录未全部被唯一匹配");
        }
        return facts;
    }

    private static List<String> nextChecksFor(String type) {
        return switch (type) {
            case "MATCHED" -> List.of("保存匹配证据并归档");
            case "AMOUNT_MISMATCH" -> List.of(
                    "核对原始支付报文和金额单位",
                    "核对折扣、部分退款和金额口径",
                    "确认币种及汇率转换规则");
            case "STATUS_MISMATCH" -> List.of(
                    "查询渠道订单详情和最终状态",
                    "检查本地回调、消息和状态变更日志",
                    "确认是否已经发生下游入账");
            case "FEE_MISMATCH" -> List.of(
                    "核对渠道费率和结算规则版本",
                    "确认手续费含税、扣除或四舍五入口径",
                    "对比原始渠道结算明细");
            case "LATE_ARRIVAL" -> List.of(
                    "核对账单业务日期和时区",
                    "等待下一批渠道账单",
                    "超过延迟窗口后再转人工核查");
            case "DUPLICATE_CHANNEL_RECORD" -> List.of(
                    "核对渠道流水号是否真的不同",
                    "检查账单是否重复导入",
                    "确认本地幂等键和渠道唯一性");
            case "UNMATCHED_LOCAL" -> List.of(
                    "核对账单日期、时区和导入范围",
                    "主动查询渠道订单详情",
                    "等待下一批账单确认是否延迟到账");
            case "UNMATCHED_CHANNEL" -> List.of(
                    "检查本地数据源和导出时间范围",
                    "按渠道订单号查询本地数据库",
                    "确认是否存在本地落库或消息消费失败");
            default -> List.of("查看原始账单和业务日志");
        };
    }

    private static List<String> blockedActionsFor(String type) {
        return switch (type) {
            case "MATCHED", "FEE_MISMATCH" -> List.of();
            case "AMOUNT_MISMATCH" -> List.of("直接修改账务金额", "未经核查执行退款或冲正");
            case "STATUS_MISMATCH" -> List.of("重复发起扣款", "直接将订单标记为失败");
            case "LATE_ARRIVAL" -> List.of("将临时缺失直接认定为支付失败", "据此重复发起支付");
            case "DUPLICATE_CHANNEL_RECORD" -> List.of("重复入账", "重复执行退款");
            case "UNMATCHED_LOCAL" -> List.of("直接发起退款", "重复发起扣款");
            case "UNMATCHED_CHANNEL" -> List.of("直接冲正渠道交易", "未经核查修改本地余额");
            default -> List.of("执行不可逆资金动作");
        };
    }

    private static Map<String, Object> evidence(Record local, Record channel) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("orderNo", local != null ? local.orderNo() : channel.orderNo());
        evidence.put("localRow", local == null ? null : local.rowNumber());
        evidence.put("channelRow", channel == null ? null : channel.rowNumber());
        evidence.put("amountDifference", local == null || channel == null
                ? null : amountDifference(local, channel));
        evidence.put("timeDifferenceMinutes", local == null || channel == null
                ? null : timeDifference(local, channel));
        return evidence;
    }

    private static Map<String, Object> stats(List<Map<String, Object>> results) {
        Map<String, Long> byType = results.stream().collect(Collectors.groupingBy(
                result -> String.valueOf(result.get("discrepancyType")),
                LinkedHashMap::new,
                Collectors.counting()));
        long matched = byType.getOrDefault("MATCHED", 0L);
        long attention = results.size() - matched;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", results.size());
        stats.put("matched", matched);
        stats.put("attention", attention);
        stats.put("manualRequired", results.stream()
                .filter(item -> {
                    Object manual = item.get("manualIntervention");
                    return manual instanceof Map<?, ?> manualMap
                            && Boolean.TRUE.equals(manualMap.get("required"));
                })
                .count());
        stats.put("byType", byType);
        return stats;
    }

    private static String reasonFor(String type) {
        return switch (type) {
            case "MATCHED" -> "订单号、金额、状态和时间窗口均满足当前匹配规则。";
            case "AMOUNT_MISMATCH" -> "订单号相同，但本地金额与渠道金额存在差异。";
            case "STATUS_MISMATCH" -> "订单号和金额可关联，但两边交易状态不一致。";
            case "FEE_MISMATCH" -> "订单号和支付金额一致，但手续费字段存在差异。";
            case "LATE_ARRIVAL" -> "订单号和金额一致，但两边流水相差超过 24 小时。";
            case "DUPLICATE_CHANNEL_RECORD" -> "渠道账单中存在相同订单号的额外流水。";
            case "UNMATCHED_LOCAL" -> "本地流水暂未在渠道账单中找到对应记录。";
            case "UNMATCHED_CHANNEL" -> "渠道流水暂未在本地流水中找到对应记录。";
            default -> "需要人工查看原始账单和业务日志。";
        };
    }

    private static String actionFor(String type) {
        return switch (type) {
            case "MATCHED" -> "记录匹配证据，进入正常归档。";
            case "LATE_ARRIVAL" -> "等待下一批账单；超过延迟窗口后转人工。";
            case "AMOUNT_MISMATCH", "FEE_MISMATCH" -> "核对原始报文、币种和手续费规则，不自动改账。";
            case "STATUS_MISMATCH" -> "查询渠道详情和本地状态事件，禁止重复扣款。";
            case "DUPLICATE_CHANNEL_RECORD" -> "核对渠道流水唯一性，确认是否为重复入账。";
            case "UNMATCHED_LOCAL" -> "等待补账单或主动查询渠道，不直接发起退款。";
            case "UNMATCHED_CHANNEL" -> "检查本地导入范围、账单日期和订单号映射。";
            default -> "转人工核查。";
        };
    }

    private static JsonNode firstExisting(JsonNode root, String... names) {
        if (root == null || !root.isObject()) {
            return null;
        }
        for (String name : names) {
            if (root.has(name)) {
                return root.get(name);
            }
        }
        return null;
    }

    public record Record(String source, int rowNumber, String orderNo, String tradeNo,
                         BigDecimal amount, String currency, String status, BigDecimal fee,
                         String time, Instant parsedTime, BigDecimal refundAmount,
                         Map<String, Object> raw) {
        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source", source);
            value.put("rowNumber", rowNumber);
            value.put("orderNo", orderNo);
            value.put("tradeNo", tradeNo);
            value.put("amount", amount);
            value.put("currency", currency);
            value.put("status", status);
            value.put("fee", fee);
            value.put("time", time);
            value.put("refundAmount", refundAmount);
            value.put("raw", raw);
            return value;
        }
    }
}
