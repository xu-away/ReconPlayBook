const state = {
    local: null,
    channel: null,
    result: null,
    filtered: [],
    detailCaseId: null
};

const $ = (selector) => document.querySelector(selector);

function formatMoney(value) {
    if (value === null || value === undefined || value === "") return "—";
    return Number(value).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatType(type) {
    const labels = {
        MATCHED: "完全匹配",
        AMOUNT_MISMATCH: "金额不一致",
        STATUS_MISMATCH: "状态不一致",
        FEE_MISMATCH: "手续费不一致",
        LATE_ARRIVAL: "延迟到账",
        DUPLICATE_CHANNEL_RECORD: "渠道重复流水",
        UNMATCHED_LOCAL: "本地单边记录",
        UNMATCHED_CHANNEL: "渠道单边记录"
    };
    return labels[type] || type;
}

function typeClass(type, risk) {
    if (type === "MATCHED") return "type-matched";
    if (risk === "HIGH") return "type-high";
    return "type-attention";
}

function riskClass(risk) {
    return `risk-${String(risk || "").toLowerCase()}`;
}

function manualStatusLabel(status) {
    return ({
        READY: "待人工介入",
        NOT_REQUIRED: "无需人工",
        IN_PROGRESS: "处理中",
        WAITING_EXTERNAL: "等待外部确认",
        COMPLETED: "已完成",
        REJECTED: "已驳回"
    })[status] || status || "—";
}

const CASE_STATUS_LABELS = {
    READY: "待处理",
    INVESTIGATING: "调查中",
    WAITING_EXTERNAL: "等待外部确认",
    PENDING_APPROVAL: "待审批",
    AUTO_PROCESSING: "自动处理中",
    RESOLVED: "已解决",
    REJECTED: "已驳回"
};

const AUDIT_ACTION_LABELS = {
    CASE_CREATED: "创建差错单",
    CASE_STATUS_CHANGED: "状态流转",
    MANUAL_INTERVENTION: "人工处理",
    LOOKUP_TASK_CREATED: "创建查单任务",
    LOOKUP_TASK_COMPLETED: "查单任务完成",
    BUSINESS_EVENT_RESENT: "补发业务事件",
    EVENT_IDEMPOTENT_REPLAY: "幂等拦截"
};

function caseStatusLabel(status) {
    return CASE_STATUS_LABELS[status] || status || "未知";
}

function caseStatusClass(status) {
    return `case-status-${String(status || "").toLowerCase()}`;
}

function taskStatusLabel(status) {
    return ({
        RUNNING: "执行中",
        SUCCEEDED: "查单成功",
        NOT_FOUND: "暂未找到"
    })[status] || status || "未知";
}

function caseActionLabel(status) {
    return ({
        INVESTIGATING: "开始调查",
        WAITING_EXTERNAL: "等待外部确认",
        PENDING_APPROVAL: "提交审批",
        AUTO_PROCESSING: "进入自动处理",
        RESOLVED: "标记已解决",
        REJECTED: "驳回差错单"
    })[status] || caseStatusLabel(status);
}

function formatTime(value) {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
}

function defaultEventType(item) {
    return item.channel?.status === "REFUNDED" ? "REFUND_SUCCEEDED" : "PAYMENT_SUCCEEDED";
}

function applyCaseView(item, view) {
    if (view?.caseStatus) item.caseStatus = view.caseStatus;
    if (view?.workflow) item.workflow = view.workflow;
    if (view?.manualIntervention) {
        item.manualIntervention = view.manualIntervention;
        if (item.playbook) item.playbook.manualIntervention = view.manualIntervention;
    }
}

function orderOf(item) {
    return item.local?.orderNo || item.channel?.orderNo || "—";
}

function setSource(source, side) {
    state[side] = source;
    const prefix = side === "local" ? "local" : "channel";
    $(`#${prefix}FileName`).textContent = source.name;
    $(`#${prefix}FileMeta`).textContent = `${source.content.split(/\r?\n/).filter(Boolean).length} 行 · 已载入`;
    const badge = $(`#${prefix}Format`);
    badge.textContent = source.format.toUpperCase();
    badge.classList.add("ready");
    updateRunState();
}

function updateRunState() {
    const ready = Boolean(state.local && state.channel);
    $("#runRecon").disabled = !ready;
    $("#runHint").textContent = ready
        ? "两份账单已就绪，可以开始匹配。"
        : "先载入 Demo，或选择两份账单文件。";
}

function readFile(file, side) {
    const reader = new FileReader();
    reader.onload = () => {
        const format = file.name.toLowerCase().endsWith(".json") ? "json" : "csv";
        setSource({ name: file.name, format, content: reader.result }, side);
    };
    reader.onerror = () => showToast("文件读取失败，请重试。");
    reader.readAsText(file, "UTF-8");
}

function showToast(message) {
    const toast = $("#toast");
    toast.textContent = message;
    toast.classList.add("show");
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => toast.classList.remove("show"), 2600);
}

async function loadDemo() {
    try {
        const response = await fetch("/api/demo");
        const demo = await response.json();
        setSource({ name: demo.localName, format: demo.localFormat, content: demo.localContent }, "local");
        setSource({ name: demo.channelName, format: demo.channelFormat, content: demo.channelContent }, "channel");
        showToast("Demo 账单已载入。");
    } catch (error) {
        showToast("Demo 载入失败，请检查服务是否正在运行。");
    }
}

async function runReconciliation() {
    if (!state.local || !state.channel) return;
    const button = $("#runRecon");
    button.disabled = true;
    button.innerHTML = "<span>…</span>匹配中";
    try {
        const response = await fetch("/api/reconcile", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                localName: state.local.name,
                localFormat: state.local.format,
                localContent: state.local.content,
                channelName: state.channel.name,
                channelFormat: state.channel.format,
                channelContent: state.channel.content
            })
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "匹配失败");
        state.result = data;
        $("#resultsSection").classList.remove("is-hidden");
        $("#localSummary").textContent = data.localFile;
        $("#channelSummary").textContent = data.channelFile;
        renderStats(data);
        renderTable();
        $("#exportCsv").disabled = false;
        showToast("匹配完成，结果已生成。");
        window.scrollTo({ top: document.body.scrollHeight, behavior: "smooth" });
    } catch (error) {
        showToast(error.message || "匹配失败，请检查账单格式。");
    } finally {
        button.disabled = false;
        button.innerHTML = "<span>▶</span>开始匹配";
        updateRunState();
    }
}

function renderStats(data) {
    const stats = data.stats || {};
    const results = data.results || [];
    const active = item => !["RESOLVED", "REJECTED"].includes(item.caseStatus);
    $("#statTotal").textContent = stats.total || results.length;
    $("#statMatched").textContent = stats.matched || results.filter(item => item.discrepancyType === "MATCHED").length;
    $("#statAttention").textContent = results.filter(item => item.discrepancyType !== "MATCHED" && active(item)).length;
    $("#statHighRisk").textContent = results.filter(item => item.risk === "HIGH" && active(item)).length;
    $("#statManual").textContent = results.filter(item => item.manualIntervention?.required && active(item)).length;
}

function renderTable() {
    const filter = $("#resultFilter").value;
    const all = state.result?.results || [];
    state.filtered = all.filter(item => {
        if (filter === "MATCHED") return item.discrepancyType === "MATCHED";
        if (filter === "ATTENTION") return item.discrepancyType !== "MATCHED";
        if (filter === "HIGH") return item.risk === "HIGH";
        if (filter === "MANUAL") {
            return item.manualIntervention?.required
                && !["RESOLVED", "REJECTED"].includes(item.caseStatus);
        }
        return true;
    });
    const body = $("#resultBody");
    body.innerHTML = "";
    $("#emptyResults").classList.toggle("is-hidden", state.filtered.length !== 0);
    state.filtered.forEach((item, index) => {
        const localAmount = item.local?.amount;
        const channelAmount = item.channel?.amount;
        const row = document.createElement("tr");
        row.innerHTML = `
            <td><span class="order-no">${escapeHtml(orderOf(item))}</span>
                <span class="trade-no">${escapeHtml(item.local?.tradeNo || item.channel?.tradeNo || "无流水号")}</span></td>
            <td><span class="type-badge ${typeClass(item.discrepancyType, item.risk)}">${formatType(item.discrepancyType)}</span></td>
            <td class="playbook-cell">${escapeHtml(item.playbook?.name || "默认差错处理剧本")}
                <span class="trade-no">${escapeHtml(item.playbook?.code || "—")}</span></td>
            <td class="amount">${formatMoney(localAmount)}</td>
            <td class="amount ${localAmount !== undefined && channelAmount !== undefined && Number(localAmount) !== Number(channelAmount) ? "amount-diff" : ""}">${formatMoney(channelAmount)}</td>
            <td><span class="risk-badge ${riskClass(item.risk)}">${item.risk}</span></td>
            <td><span class="case-status ${caseStatusClass(item.caseStatus)}">${caseStatusLabel(item.caseStatus)}</span>
                <span class="trade-no">${escapeHtml(item.manualIntervention?.status ? manualStatusLabel(item.manualIntervention.status) : "工作流")}</span></td>
            <td class="next-action">${escapeHtml(item.recommendedAction || "—")}</td>
        `;
        row.addEventListener("click", () => openDetail(item));
        body.appendChild(row);
    });
}

function openDetail(item) {
    state.detailCaseId = item.caseId;
    $("#detailTitle").textContent = `${formatType(item.discrepancyType)} · ${orderOf(item)}`;
    const evidence = item.evidence || {};
    const local = item.local;
    const channel = item.channel;
    const playbook = item.playbook || {};
    const manual = item.manualIntervention || playbook.manualIntervention || {};
    const workflow = item.workflow || {};
    const allowedStatuses = workflow.allowedNextStatuses || [];
    const currentStatus = item.caseStatus || workflow.status || "READY";
    const conditions = Object.entries(playbook.conditions || {});
    const conditionHtml = conditions.length
        ? conditions.map(([key, value]) => `<span>${escapeHtml(key)} = ${escapeHtml(value)}</span>`).join("")
        : "<span>按差异类型命中默认条件</span>";
    const pathHtml = (playbook.processingPath || []).map(step => `
        <div class="path-item">
            <span class="path-step">${step.order}</span>
            <div><strong>${escapeHtml(step.action || "—")}</strong><small>${escapeHtml(step.owner || "—")} · ${escapeHtml(step.mode || "—")}</small></div>
        </div>
    `).join("");
    const manualHistoryHtml = manual.operator ? `
        <div class="manual-history">
            <span>最近操作：${escapeHtml(manual.operator)} · ${escapeHtml(manualStatusLabel(manual.status))}</span>
            ${manual.note ? `<span>${escapeHtml(manual.note)}</span>` : ""}
        </div>
    ` : "";
    const manualHtml = manual.required ? `
        <div class="manual-form">
            <label class="form-field"><span>操作人</span><input id="manualOperator" type="text" placeholder="填写姓名或工号"></label>
            <label class="form-field"><span>处理状态</span>
                <select id="manualStatus">
                    <option value="IN_PROGRESS">处理中</option>
                    <option value="WAITING_EXTERNAL">等待外部确认</option>
                    <option value="COMPLETED">已完成</option>
                    <option value="REJECTED">已驳回</option>
                </select>
            </label>
            <label class="form-field"><span>处理备注</span><textarea id="manualNote" rows="3" placeholder="记录核查结论或下一步安排"></textarea></label>
            <button id="submitManual" class="button button-primary"><span>✓</span>提交人工介入</button>
        </div>
        ${manualHistoryHtml}
    ` : `<div class="manual-note">当前剧本暂不要求人工介入。${manual.trigger ? ` 超时后：${escapeHtml(manual.trigger)}` : ""}</div>`;
    $("#detailContent").innerHTML = `
        <div class="detail-section">
            <div class="detail-label">差错单状态</div>
            <div class="case-status-line">
                <span class="case-status ${caseStatusClass(currentStatus)}">${caseStatusLabel(currentStatus)}</span>
                <span class="detail-copy">当前状态：${escapeHtml(currentStatus)}</span>
            </div>
            <div class="workflow-actions">
                ${allowedStatuses.length
                    ? allowedStatuses.map(status => `<button class="button button-secondary button-compact" data-transition-status="${escapeHtml(status)}">${escapeHtml(caseActionLabel(status))}</button>`).join("")
                    : `<span class="operation-muted">当前状态没有可用流转动作。</span>`}
            </div>
            <label class="form-field workflow-note-field"><span>操作人</span><input id="workflowOperator" type="text" placeholder="填写姓名或工号"></label>
            <label class="form-field workflow-note-field"><span>操作备注</span><textarea id="workflowNote" rows="2" placeholder="记录本次状态变更或查单结论"></textarea></label>
        </div>
        <div class="detail-section">
            <div class="detail-label">风险等级</div>
            <p class="detail-value"><span class="risk-badge ${riskClass(item.risk)}">${item.risk}</span></p>
            <p class="detail-copy">${escapeHtml(item.reason || "")}</p>
        </div>
        <div class="detail-section">
            <div class="detail-label">建议动作</div>
            <p class="detail-value">${escapeHtml(item.recommendedAction || "—")}</p>
        </div>
        <div class="detail-section">
            <div class="detail-label">处理剧本</div>
            <div class="playbook-title"><strong>${escapeHtml(playbook.name || "默认差错处理剧本")}</strong><span class="risk-badge ${riskClass(playbook.riskLevel || item.risk)}">${escapeHtml(playbook.automation || "MANUAL")}</span></div>
            <p class="detail-copy">剧本编号：${escapeHtml(playbook.code || "—")} · 人工状态：<span class="manual-status">${escapeHtml(manualStatusLabel(manual.status))}</span></p>
            <div class="playbook-block"><strong>命中条件</strong><div class="condition-list">${conditionHtml}</div></div>
            <div class="playbook-block"><strong>处理路径</strong><div class="path-list">${pathHtml || "<span>暂无处理步骤</span>"}</div></div>
            <div class="playbook-block"><strong>人工介入</strong>${manualHtml}</div>
        </div>
        <div class="detail-section">
            <div class="detail-label">差异诊断</div>
            <p class="detail-value">${escapeHtml(item.diagnosis?.primaryCause || "—")}</p>
            <p class="detail-copy">置信度：${escapeHtml(item.diagnosis?.confidence || "—")} · 影响：${escapeHtml(item.diagnosis?.impact || "—")}</p>
            <div class="diagnosis-list">
                <strong>依据</strong>
                ${(item.diagnosis?.evidenceFacts || []).map(fact => `<span>• ${escapeHtml(fact)}</span>`).join("")}
                <strong>下一步核查</strong>
                ${(item.diagnosis?.nextChecks || []).map(check => `<span>• ${escapeHtml(check)}</span>`).join("")}
                ${(playbook.forbiddenActions || []).length ? `<strong>禁止动作</strong>${playbook.forbiddenActions.map(action => `<span class="blocked-action">× ${escapeHtml(action)}</span>`).join("")}` : ""}
            </div>
        </div>
        <div class="detail-section">
            <div class="detail-label">匹配证据</div>
            <div class="evidence-grid">
                <div class="evidence-cell"><span>订单号</span><strong>${escapeHtml(evidence.orderNo || "—")}</strong></div>
                <div class="evidence-cell"><span>金额差</span><strong>${evidence.amountDifference === null || evidence.amountDifference === undefined ? "—" : formatMoney(evidence.amountDifference)}</strong></div>
                <div class="evidence-cell"><span>本地行号</span><strong>${evidence.localRow ?? "—"}</strong></div>
                <div class="evidence-cell"><span>渠道行号</span><strong>${evidence.channelRow ?? "—"}</strong></div>
                <div class="evidence-cell"><span>时间差</span><strong>${evidence.timeDifferenceMinutes === null || evidence.timeDifferenceMinutes === undefined ? "—" : `${evidence.timeDifferenceMinutes} 分钟`}</strong></div>
                <div class="evidence-cell"><span>渠道流水</span><strong>${escapeHtml(channel?.tradeNo || "—")}</strong></div>
            </div>
        </div>
        <div class="detail-section">
            <div class="detail-label">任务与审计</div>
            <div class="operation-actions">
                <button id="lookupTaskButton" class="button button-secondary"><span>⌕</span>主动查单</button>
                ${item.discrepancyType === "STATUS_MISMATCH" ? `
                    <label class="operation-select"><span>事件</span>
                        <select id="eventType">
                            <option value="PAYMENT_SUCCEEDED" ${defaultEventType(item) === "PAYMENT_SUCCEEDED" ? "selected" : ""}>支付成功</option>
                            <option value="REFUND_SUCCEEDED" ${defaultEventType(item) === "REFUND_SUCCEEDED" ? "selected" : ""}>退款成功</option>
                        </select>
                    </label>
                    <button id="resendEventButton" class="button button-primary" ${["PENDING_APPROVAL", "AUTO_PROCESSING"].includes(currentStatus) ? "" : "disabled"}><span>↻</span>自动补发事件</button>
                ` : ""}
            </div>
            ${item.discrepancyType === "STATUS_MISMATCH" && !["PENDING_APPROVAL", "AUTO_PROCESSING"].includes(currentStatus)
                ? `<p class="operation-muted">差错单进入“待审批”后才能补发业务事件。</p>` : ""}
            <div id="lookupTasks" class="operation-result"><span class="operation-muted">查单任务加载中…</span></div>
            <div id="auditTimeline" class="audit-timeline"><span class="operation-muted">审计记录加载中…</span></div>
        </div>
        <div class="detail-section">
            <div class="detail-label">原始字段</div>
            <pre class="raw-block">${escapeHtml(JSON.stringify({ local: local?.raw || null, channel: channel?.raw || null }, null, 2))}</pre>
        </div>
    `;
    $("#detailPanel").classList.add("open");
    $("#detailPanel").setAttribute("aria-hidden", "false");
    const submitManual = $("#submitManual");
    if (submitManual) {
        submitManual.addEventListener("click", () => submitManualIntervention(item));
    }
    document.querySelectorAll("[data-transition-status]").forEach(button => {
        button.addEventListener("click", () => transitionCase(item, button.dataset.transitionStatus));
    });
    $("#lookupTaskButton").addEventListener("click", () => createLookupTask(item));
    const resendEventButton = $("#resendEventButton");
    if (resendEventButton) {
        resendEventButton.addEventListener("click", () => resendEvent(item));
    }
    loadCaseOperations(item);
}

async function submitManualIntervention(item) {
    const operator = $("#manualOperator")?.value.trim();
    const status = $("#manualStatus")?.value || "IN_PROGRESS";
    const note = $("#manualNote")?.value.trim() || "";
    if (!operator) {
        showToast("请先填写操作人。");
        return;
    }
    const button = $("#submitManual");
    button.disabled = true;
    try {
        const response = await fetch("/api/manual-interventions", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ caseId: item.caseId, operator, status, note })
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "人工介入提交失败");
        applyCaseView(item, data);
        renderStats(state.result);
        renderTable();
        openDetail(item);
        showToast("人工介入状态已更新。");
    } catch (error) {
        showToast(error.message || "人工介入提交失败。");
    } finally {
        if ($("#submitManual")) $("#submitManual").disabled = false;
    }
}

async function transitionCase(item, targetStatus) {
    const operator = $("#workflowOperator")?.value.trim() || "operator";
    const note = $("#workflowNote")?.value.trim() || "";
    const buttons = document.querySelectorAll("[data-transition-status]");
    buttons.forEach(button => button.disabled = true);
    try {
        const response = await fetch("/api/case-status", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ caseId: item.caseId, status: targetStatus, actor: operator, note })
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "状态流转失败");
        applyCaseView(item, data);
        renderStats(state.result);
        renderTable();
        openDetail(item);
        showToast(`差错单已流转至“${caseStatusLabel(targetStatus)}”。`);
    } catch (error) {
        showToast(error.message || "状态流转失败。");
        buttons.forEach(button => button.disabled = false);
    }
}

async function createLookupTask(item) {
    const button = $("#lookupTaskButton");
    const operator = $("#workflowOperator")?.value.trim() || "operator";
    if (!button) return;
    button.disabled = true;
    button.innerHTML = "<span>…</span>查单中";
    try {
        const response = await fetch("/api/lookup-tasks", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ caseId: item.caseId, operator })
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "主动查单失败");
        if (data.status === "SUCCEEDED") {
            showToast("主动查单完成，已取得渠道记录。");
        } else {
            showToast("主动查单完成，但暂未找到渠道记录。");
        }
        applyCaseView(item, data);
        renderStats(state.result);
        renderTable();
        openDetail(item);
    } catch (error) {
        showToast(error.message || "主动查单失败。");
        button.disabled = false;
        button.innerHTML = "<span>⌕</span>主动查单";
    }
}

async function resendEvent(item) {
    const button = $("#resendEventButton");
    const operator = $("#workflowOperator")?.value.trim() || "operator";
    const eventType = $("#eventType")?.value || defaultEventType(item);
    const note = $("#workflowNote")?.value.trim() || "";
    if (!button) return;
    button.disabled = true;
    button.innerHTML = "<span>…</span>补发中";
    try {
        const response = await fetch("/api/events/resend", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ caseId: item.caseId, eventType, operator, note })
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "补发事件失败");
        item.caseStatus = data.caseStatus || item.caseStatus;
        if (data.workflow) item.workflow = data.workflow;
        renderStats(state.result);
        renderTable();
        openDetail(item);
        showToast(data.message || "业务事件已补发。");
    } catch (error) {
        showToast(error.message || "补发事件失败。");
        button.disabled = false;
        button.innerHTML = "<span>↻</span>自动补发事件";
    }
}

async function loadCaseOperations(item) {
    try {
        const [tasksResponse, auditResponse] = await Promise.all([
            fetch(`/api/lookup-tasks?caseId=${encodeURIComponent(item.caseId)}`),
            fetch(`/api/audit?caseId=${encodeURIComponent(item.caseId)}`)
        ]);
        const tasks = await tasksResponse.json();
        const audit = await auditResponse.json();
        if (state.detailCaseId !== item.caseId) return;
        renderLookupTasks(tasks.tasks || []);
        renderAuditTimeline(audit.entries || []);
    } catch (error) {
        if (state.detailCaseId !== item.caseId) return;
        $("#lookupTasks").innerHTML = `<span class="operation-muted">任务记录暂时不可用。</span>`;
        $("#auditTimeline").innerHTML = `<span class="operation-muted">审计记录暂时不可用。</span>`;
    }
}

function renderLookupTasks(tasks) {
    const container = $("#lookupTasks");
    if (!container) return;
    if (!tasks.length) {
        container.innerHTML = `<div class="operation-heading">主动查单任务</div><span class="operation-muted">尚未创建查单任务。</span>`;
        return;
    }
    container.innerHTML = `
        <div class="operation-heading">主动查单任务</div>
        ${tasks.slice().sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).map(task => `
            <div class="task-row">
                <span class="task-status ${caseStatusClass(task.status)}">${escapeHtml(taskStatusLabel(task.status))}</span>
                <div><strong>${escapeHtml(task.queryKey || "—")}</strong><small>${escapeHtml(task.message || "")}</small></div>
                <time>${escapeHtml(formatTime(task.completedAt || task.createdAt))}</time>
            </div>
        `).join("")}
    `;
}

function renderAuditTimeline(entries) {
    const container = $("#auditTimeline");
    if (!container) return;
    if (!entries.length) {
        container.innerHTML = `<div class="operation-heading">操作记录</div><span class="operation-muted">尚未产生操作记录。</span>`;
        return;
    }
    container.innerHTML = `
        <div class="operation-heading">操作记录</div>
        ${entries.slice().sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).map(entry => `
            <div class="audit-item">
                <span class="audit-dot"></span>
                <div>
                    <div class="audit-meta"><strong>${escapeHtml(AUDIT_ACTION_LABELS[entry.action] || entry.action)}</strong><span>${escapeHtml(entry.actor || "SYSTEM")}</span><time>${escapeHtml(formatTime(entry.createdAt))}</time></div>
                    <p>${escapeHtml(entry.message || "")}</p>
                </div>
            </div>
        `).join("")}
    `;
}

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, character => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;"
    }[character]));
}

function exportCsv() {
    const rows = [["orderNo", "discrepancyType", "risk", "localAmount", "channelAmount", "recommendedAction"]];
    (state.result?.results || []).forEach(item => rows.push([
        orderOf(item), item.discrepancyType, item.risk,
        item.local?.amount ?? "", item.channel?.amount ?? "", item.recommendedAction || ""
    ]));
    const csv = rows.map(row => row.map(value => `"${String(value).replaceAll('"', '""')}"`).join(",")).join("\n");
    const blob = new Blob(["\ufeff" + csv], { type: "text/csv;charset=utf-8" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "reconplaybook-result.csv";
    link.click();
    URL.revokeObjectURL(link.href);
}

$("#localFile").addEventListener("change", event => {
    if (event.target.files[0]) readFile(event.target.files[0], "local");
});
$("#channelFile").addEventListener("change", event => {
    if (event.target.files[0]) readFile(event.target.files[0], "channel");
});
$("#loadDemo").addEventListener("click", loadDemo);
$("#runRecon").addEventListener("click", runReconciliation);
$("#resultFilter").addEventListener("change", renderTable);
$("#exportCsv").addEventListener("click", exportCsv);
$("#closeDetail").addEventListener("click", () => {
    state.detailCaseId = null;
    $("#detailPanel").classList.remove("open");
    $("#detailPanel").setAttribute("aria-hidden", "true");
});
