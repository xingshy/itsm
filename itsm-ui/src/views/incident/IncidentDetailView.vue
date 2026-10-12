<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import http, { getToken } from "../../api/http";
import IncidentPersonSelect from "../../components/incident/IncidentPersonSelect.vue";
import {
  incidentApi,
  statuses,
  actions,
  activityLabels,
  detailLabels,
  date,
  overdue,
  type Incident,
  type Options,
  type Recipient,
} from "../../api/incident";
const route = useRoute(),
  router = useRouter();
const create = computed(() => route.path.endsWith("/create"));
const ticket = ref<Incident>();
const loading = ref(false),
  saving = ref(false);
const options = ref<Options>({ categories: [], departments: [], roles: [] });
const open = ref(false),
  operation = ref(""),
  thread = ref<string>();
const form = reactive<Record<string, any>>({});
const recipients = ref<Recipient[]>([]);
const recipientKeyword = ref("");
const recipientPage = ref(1),
  recipientTotal = ref(0);
const me = ref("");
const canReport = ref(false);
const activeTab = ref("comments");
const selectedThread = ref<string>();
const kinds = [
  "comments",
  "forwards",
  "assignments",
  "responsibilities",
  "cycles",
  "activities",
  "attachments",
];
const sections = reactive<
  Record<
    string,
    { records: Record<string, any>[]; total: number; page: number }
  >
>(
  Object.fromEntries(kinds.map((k) => [k, { records: [], total: 0, page: 1 }])),
);
const replyRows = ref<Record<string, any>[]>([]),
  replyTotal = ref(0),
  replyPage = ref(1);
const timelineAction = ref(""),
  timelineActor = ref("");
const basics = computed(() => create.value || operation.value === "edit");
const peopleAction = computed(
  () =>
    ["assign", "transfer", "forward"].includes(operation.value) ||
    (operation.value === "reopen" && options.value.canAssign === true),
);
const reasonRequired = computed(
  () =>
    !basics.value &&
    ![
      "accept",
      "submit",
      "resolve",
      "supplement",
      "processing-comment",
      "reply",
    ].includes(operation.value),
);
const commentLabels: Record<string, string> = {
  SUPPLEMENT: "补充说明",
  PROCESSING: "处理意见",
  SOLUTION: "解决方案",
};
const threadLabels: Record<string, string> = {
  OPEN: "进行中",
  ENDED: "已结束",
  WITHDRAWN: "已撤回",
};
const signatures = new Map<string, string>();
function key(body: unknown) {
  const signature = JSON.stringify(body);
  if (!signatures.has(signature))
    signatures.set(signature, crypto.randomUUID());
  return signatures.get(signature)!;
}
function initial() {
  Object.assign(form, {
    reporterId: "",
    attachmentIds: [],
    title: "",
    description: "",
    category: "",
    impact: "SINGLE",
    urgency: "LOW",
    reason: "",
    recipientIds: [],
    targetUserId: "",
    departmentId: "",
    roles: [],
    analysis: "",
    measures: "",
    result: "",
    verification: "",
    content: "",
    progress: "",
    remaining: "",
    approved: true,
    externalCondition: true,
    expectedResumeAt: "",
    occurredAt: "",
  });
}
function error(e: unknown) {
  ElMessage.error((e as Error).message);
}
async function loadSection(kind: string) {
  if (!ticket.value) return;
  const s = sections[kind];
  const result = await incidentApi.records(ticket.value.id, kind, {
    page: s.page,
    size: 20,
    ...(kind === "activities"
      ? { action: timelineAction.value, actorId: timelineActor.value }
      : {}),
  });
  s.records = result.records;
  s.total = result.total;
}
async function reload() {
  if (create.value) return;
  loading.value = true;
  try {
    ticket.value = await incidentApi.detail(String(route.params.id));
    await Promise.all(kinds.map(loadSection));
  } catch (e) {
    error(e);
  } finally {
    loading.value = false;
  }
}
async function findRecipients(
  keyword = recipientKeyword.value,
  append = false,
) {
  try {
    recipientKeyword.value = keyword;
    if (!append) recipientPage.value = 1;
    const result = await incidentApi.recipients({
      keyword,
      departmentId:
        operation.value === "forward" ? undefined : form.departmentId,
      roles: form.roles?.join(","),
      page: recipientPage.value,
      size: 20,
    });
    recipients.value = append
      ? [...recipients.value, ...result.records]
      : result.records;
    recipientTotal.value = result.total;
  } catch (e) {
    error(e);
  }
}
function show(op: string, id?: string) {
  operation.value = op;
  thread.value = id;
  Object.keys(form).forEach((k) => delete form[k]);
  initial();
  if (op === "edit" && ticket.value) {
    for (const k of [
      "title",
      "description",
      "category",
      "impact",
      "urgency",
      "occurredAt",
      "priority",
    ])
      form[k] = (ticket.value as any)[k] || "";
  }
  open.value = true;
  if (peopleAction.value) findRecipients("");
  if (op === "reply" && id) {
    threadAttachmentPage.value = 1;
    loadThreadAttachments(id);
  }
}
function prepare() {
  if (create.value || operation.value === "edit") {
    const b: Record<string, unknown> = {
      title: form.title,
      description: form.description,
      category: form.category,
      impact: form.impact,
      urgency: form.urgency,
      occurredAt: form.occurredAt
        ? new Date(form.occurredAt).toISOString()
        : null,
    };
    if (!create.value) {
      b.priority = form.priority;
      b.reason = form.reason;
    }
    return b;
  }
  const op = operation.value;
  const b: Record<string, unknown> = {};
  if (reasonRequired.value) b.reason = form.reason;
  if (
    ["assign", "transfer"].includes(op) ||
    (op === "reopen" && form.targetUserId)
  ) {
    b.targetUserId = form.targetUserId;
    b.departmentId = form.departmentId;
  }
  if (op === "transfer") {
    b.progress = form.progress;
    b.remaining = form.remaining;
  }
  if (op === "forward") b.recipientIds = form.recipientIds;
  if (op === "return-decision") b.approved = form.approved;
  if (op === "suspend") {
    b.expectedResumeAt = form.expectedResumeAt
      ? new Date(form.expectedResumeAt).toISOString()
      : "";
    b.externalCondition = form.externalCondition;
  }
  if (op === "resolve")
    for (const k of ["analysis", "measures", "result", "verification"])
      b[k] = form[k];
  if (["supplement", "processing-comment", "reply"].includes(op)) {
    b.content = form.content;
    if (op !== "reply")
      b.type = op === "supplement" ? "SUPPLEMENT" : "PROCESSING";
  }
  if (form.correctsCommentId) b.correctsCommentId = form.correctsCommentId;
  if (["supplement", "processing-comment", "reply", "resolve"].includes(op))
    b.attachmentIds = form.attachmentIds || [];
  return b;
}
async function save(draft = false) {
  if (saving.value) return;
  saving.value = true;
  try {
    let body = prepare();
    if (create.value && canReport.value && form.reporterId)
      body.reporterId = form.reporterId;
    if (create.value) {
      body = { ...body, draft };
      const result = await incidentApi.create(body, key(body));
      ElMessage.success(draft ? "草稿已保存" : "事件已提交");
      await router.replace("/incidents/" + result.id);
      return;
    }
    const t = ticket.value!;
    body.version = t.version;
    const op = operation.value;
    let result: Incident;
    if (op === "edit")
      result = await incidentApi.edit(t.id, body, key({ id: t.id, op, body }));
    else {
      let path = op;
      if (
        ["accept", "withdraw", "return-request", "return-decision"].includes(op)
      )
        path = `assignments/${t.pendingTask!.id}/${op}`;
      if (["reply", "forward-withdraw"].includes(op))
        path = `forwards/${thread.value}/${op === "reply" ? "replies" : "withdraw"}`;
      if (["supplement", "processing-comment"].includes(op)) path = "comments";
      if (op === "assign") path = "assignments";
      if (op === "transfer") path = "transfers";
      if (op === "forward") path = "forwards";
      if (op === "delete-draft") path = "draft";
      result = await incidentApi.mutate(
        t.id,
        path,
        body,
        key({ id: t.id, path, body }),
        op === "delete-draft" ? "delete" : "post",
      );
    }
    ticket.value = result;
    open.value = false;
    ElMessage.success("操作成功");
    if (op === "delete-draft") {
      router.push("/incidents");
      return;
    }
    await reload();
    if (selectedThread.value) await loadReplies(selectedThread.value);
  } catch (e) {
    error(e);
  } finally {
    saving.value = false;
  }
}
async function loadReplies(id: string) {
  selectedThread.value = id;
  try {
    const result = await incidentApi.records(
      ticket.value!.id,
      `forwards/${id}/replies`,
      { page: replyPage.value, size: 20 },
    );
    replyRows.value = result.records;
    replyTotal.value = result.total;
  } catch (e) {
    error(e);
  }
}
async function inspect(f: Record<string, any>) {
  selectedThread.value = f.id;
  replyPage.value = 1;
  const self = f.recipients.find(
    (r: Record<string, any>) => r.userId === me.value,
  );
  if (self && !self.readAt && f.status !== "WITHDRAWN")
    try {
      const body = { version: ticket.value!.version };
      ticket.value = await incidentApi.mutate(
        ticket.value!.id,
        `forwards/${f.id}/read`,
        body,
        key({ id: f.id, read: body }),
      );
      await loadSection("forwards");
    } catch (e) {
      error(e);
    }
  await loadReplies(f.id);
}
function solution(content: string) {
  try {
    const s = JSON.parse(content);
    return `原因分析：${s.analysis}\n采取措施：${s.measures}\n处理结果：${s.result}\n验证说明：${s.verification}`;
  } catch {
    return content;
  }
}
const attachmentThread = ref<string>();
const fileInput = ref<HTMLInputElement>();
async function upload(event: Event) {
  const files = (event.target as HTMLInputElement).files;
  if (!files?.length || !ticket.value) return;
  const data = new FormData();
  Array.from(files).forEach((f) => data.append("files", f));
  data.append("version", String(ticket.value.version));
  if (attachmentThread.value) data.append("threadId", attachmentThread.value);
  saving.value = true;
  try {
    const uploaded = await http.post<never, { attachmentIds: string[] }>(
      `/incidents/${ticket.value.id}/attachments`,
      data,
      {
        headers: {
          "Idempotency-Key": key({
            ticket: ticket.value.id,
            thread: attachmentThread.value,
            version: ticket.value.version,
            files: Array.from(files).map((f) => [
              f.name,
              f.size,
              f.lastModified,
            ]),
          }),
        },
      },
    );
    if (open.value)
      form.attachmentIds = [
        ...(form.attachmentIds || []),
        ...uploaded.attachmentIds,
      ];
    ElMessage.success("附件已上传");
    await reload();
    if (attachmentThread.value)
      await loadThreadAttachments(attachmentThread.value);
  } catch (e) {
    error(e);
  } finally {
    saving.value = false;
    (event.target as HTMLInputElement).value = "";
  }
}
const threadAttachments = ref<Record<string, any>[]>([]),
  threadAttachmentTotal = ref(0),
  threadAttachmentPage = ref(1);
async function loadThreadAttachments(id: string) {
  attachmentThread.value = id;
  try {
    const p = await incidentApi.records(ticket.value!.id, "attachments", {
      threadId: id,
      page: threadAttachmentPage.value,
      size: 20,
    });
    threadAttachments.value = p.records;
    threadAttachmentTotal.value = p.total;
  } catch (e) {
    error(e);
  }
}
async function download(a: Record<string, any>) {
  try {
    const response = await fetch(
      `/api/v1/incidents/${ticket.value!.id}/attachments/${a.id}`,
      { headers: { Authorization: `Bearer ${getToken()}` } },
    );
    if (
      !response.ok ||
      response.headers.get("content-type")?.includes("json")
    ) {
      const b = await response.json();
      throw new Error(b.message || "下载失败");
    }
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = url;
    link.download = a.filename;
    link.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    error(e);
  }
}
async function init() {
  initial();
  try {
    options.value = await incidentApi.options();
    const u = await http.get<
      never,
      { userId?: string; id?: string; roles: string[] }
    >("/auth/me");
    me.value = String(u.userId || u.id || "");
    canReport.value = u.roles.includes("SEAT");
    await reload();
  } catch (e) {
    error(e);
  }
}
onMounted(init);
watch(
  () => route.params.id,
  () => {
    kinds.forEach((k) => (sections[k].page = 1));
    selectedThread.value = undefined;
    init();
  },
);
</script>
<template>
  <div v-loading="loading">
    <div class="heading">
      <el-button @click="router.push('/incidents')">返回列表</el-button
      ><el-button v-if="!create" @click="reload">刷新</el-button>
    </div>
    <el-card v-if="create"
      ><h2>新增事件</h2>
      <el-form label-width="100px"
        ><el-form-item label="标题" required
          ><el-input
            v-model="form.title"
            maxlength="200"
            show-word-limit /></el-form-item
        ><el-form-item label="事件描述" required
          ><el-input
            v-model="form.description"
            type="textarea"
            :rows="6"
            maxlength="10000"
            show-word-limit
        /></el-form-item>
        <el-form-item label="分类" required
          ><el-select v-model="form.category"
            ><el-option
              v-for="c in options.categories"
              :key="c.code"
              :value="c.code"
              :label="c.label" /></el-select
        ></el-form-item>
        <el-form-item label="影响度" required
          ><el-radio-group v-model="form.impact"
            ><el-radio value="WIDE">广</el-radio
            ><el-radio value="PARTIAL">局部</el-radio
            ><el-radio value="SINGLE">单点</el-radio></el-radio-group
          ></el-form-item
        >
        <el-form-item label="紧急度" required
          ><el-radio-group v-model="form.urgency"
            ><el-radio value="HIGH">高</el-radio
            ><el-radio value="MEDIUM">中</el-radio
            ><el-radio value="LOW">低</el-radio></el-radio-group
          ></el-form-item
        >
        <el-form-item v-if="canReport" label="报告人"
          ><IncidentPersonSelect
            v-model="form.reporterId"
            placeholder="默认本人，可为员工代录"
            style="width: 100%" /></el-form-item
        ><el-form-item label="发生时间"
          ><el-date-picker
            v-model="form.occurredAt"
            type="datetime"
            placeholder="未知可不填"
        /></el-form-item>
        <el-form-item
          ><el-button type="primary" :loading="saving" @click="save(false)"
            >提交事件</el-button
          ><el-button :loading="saving" @click="save(true)"
            >保存草稿</el-button
          ></el-form-item
        ></el-form
      ></el-card
    >
    <template v-else-if="ticket"
      ><el-card
        ><div class="heading">
          <div>
            <small>{{ ticket.ticketNo || "草稿 #" + ticket.id }}</small>
            <h2>{{ ticket.title || "未命名草稿" }}</h2>
          </div>
          <div>
            <el-tag>{{ statuses[ticket.status] }}</el-tag>
            <el-tag>{{ ticket.priority }}</el-tag>
            <el-tag v-if="overdue(ticket)" type="danger">已超时</el-tag>
          </div>
        </div>
        <div class="buttons">
          <el-button
            v-for="op in ticket.allowedActions"
            :key="op"
            :type="
              ['assign', 'accept', 'resolve', 'submit'].includes(op)
                ? 'primary'
                : 'default'
            "
            @click="show(op)"
            >{{ actions[op] }}</el-button
          >
        </div>
        <el-alert
          v-if="ticket.status === 'PENDING_TAKEOVER'"
          title="等待目标接管；转交期间原处理人仍负责响应。解决、再次转交或挂起需先撤回请求。"
          type="info"
          :closable="false"
        />
        <el-descriptions
          v-if="ticket.pendingTask"
          :column="1"
          border
          style="margin-bottom: 16px"
          ><el-descriptions-item label="接管期限">{{
            date(ticket.pendingTask.dueAt)
          }}</el-descriptions-item
          ><el-descriptions-item label="分配/转交原因">{{
            ticket.pendingTask.reason
          }}</el-descriptions-item
          ><el-descriptions-item
            v-if="ticket.pendingTask.progress"
            label="当前进展"
            >{{ ticket.pendingTask.progress }}</el-descriptions-item
          ><el-descriptions-item
            v-if="ticket.pendingTask.remaining"
            label="未完成事项"
            >{{ ticket.pendingTask.remaining }}</el-descriptions-item
          ><el-descriptions-item
            v-if="ticket.pendingTask.returnRequested"
            label="退回申请"
            >{{ ticket.pendingTask.returnReason }}</el-descriptions-item
          ></el-descriptions
        >
        <el-descriptions :column="3" border
          ><el-descriptions-item label="创建人">{{
            ticket.creatorName
          }}</el-descriptions-item
          ><el-descriptions-item label="报告人">{{
            ticket.reporterName
          }}</el-descriptions-item
          ><el-descriptions-item label="分类">{{
            options.categories.find((c) => c.code === ticket!.category)?.label
          }}</el-descriptions-item>
          <el-descriptions-item label="当前处理人">{{
            ticket.ownerName || "—"
          }}</el-descriptions-item
          ><el-descriptions-item label="处理部门">{{
            ticket.departmentName || "—"
          }}</el-descriptions-item
          ><el-descriptions-item label="待接管人">{{
            ticket.pendingUserName || "—"
          }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{
            date(ticket.createdAt)
          }}</el-descriptions-item
          ><el-descriptions-item label="响应期限">{{
            date(ticket.responseDueAt)
          }}</el-descriptions-item
          ><el-descriptions-item label="解决期限">{{
            date(ticket.resolutionDueAt)
          }}</el-descriptions-item
          ><el-descriptions-item label="首次响应">{{
            date(ticket.firstResponseAt)
          }}</el-descriptions-item
          ><el-descriptions-item label="解决时间">{{
            date(ticket.resolvedAt)
          }}</el-descriptions-item
          ><el-descriptions-item label="处理周期">{{
            ticket.cycle
          }}</el-descriptions-item
          ><el-descriptions-item label="累计计时耗时"
            >{{
              Math.round(ticket.processingSeconds / 60)
            }}
            分钟</el-descriptions-item
          ><el-descriptions-item label="事件描述" :span="3"
            ><div class="text">
              {{ ticket.description }}
            </div></el-descriptions-item
          ></el-descriptions
        >
      </el-card>
      <el-card class="history"
        ><el-tabs v-model="activeTab"
          ><el-tab-pane label="处理信息" name="comments" /><el-tab-pane
            label="协作记录"
            name="forwards" /><el-tab-pane
            label="分配/转交"
            name="assignments" /><el-tab-pane
            label="责任区间"
            name="responsibilities" /><el-tab-pane
            label="处理周期/耗时"
            name="cycles" /><el-tab-pane
            label="生命周期"
            name="activities" /><el-tab-pane label="附件" name="attachments"
        /></el-tabs>
        <template v-if="activeTab === 'comments'"
          ><el-empty
            v-if="!sections.comments.records.length"
            description="暂无意见"
          />
          <div
            v-for="c in sections.comments.records"
            :key="c.id"
            class="record"
          >
            <strong>{{ commentLabels[c.type] }} · {{ c.authorName }}</strong
            ><small>{{ date(c.createdAt) }} · 第{{ c.cycle }}周期</small>
            <p v-if="c.correctsCommentId">
              补充更正意见 #{{ c.correctsCommentId }}
            </p>
            <p class="text">
              {{ c.type === "SOLUTION" ? solution(c.content) : c.content }}
            </p>
            <el-button
              v-if="
                c.authorId === me &&
                c.type !== 'SOLUTION' &&
                ticket.allowedActions.includes(
                  c.type === 'SUPPLEMENT' ? 'supplement' : 'processing-comment',
                )
              "
              link
              type="primary"
              @click="
                show(
                  c.type === 'SUPPLEMENT' ? 'supplement' : 'processing-comment',
                );
                form.correctsCommentId = c.id;
              "
              >补充更正</el-button
            >
          </div></template
        >
        <template v-if="activeTab === 'activities'"
          ><el-form inline
            ><el-form-item label="动作"
              ><el-select
                v-model="timelineAction"
                clearable
                style="width: 180px"
                ><el-option
                  v-for="(label, code) in activityLabels"
                  :key="code"
                  :label="label"
                  :value="code" /></el-select></el-form-item
            ><el-form-item label="操作人"
              ><IncidentPersonSelect
                v-model="timelineActor"
                style="width: 230px" /></el-form-item
            ><el-button
              @click="
                sections.activities.page = 1;
                loadSection('activities').catch(error);
              "
              >查询</el-button
            ></el-form
          >
          <el-timeline
            ><el-timeline-item
              v-for="a in sections.activities.records"
              :key="a.id"
              :timestamp="date(a.createdAt)"
              ><strong
                >{{ a.actorName }} ·
                {{ activityLabels[a.action] || a.action }}</strong
              >
              <div>
                {{ statuses[a.beforeStatus] }} → {{ statuses[a.afterStatus] }}
              </div>
              <div v-for="(value, k) in a.details" :key="k" class="text">
                {{ detailLabels[k] || k }}：{{ value }}
              </div></el-timeline-item
            ></el-timeline
          ></template
        >
        <template v-if="activeTab === 'assignments'"
          ><el-table :data="sections.assignments.records"
            ><el-table-column label="动作"
              ><template #default="{ row }">{{
                row.kind === "TRANSFER" ? "转交" : "分配"
              }}</template></el-table-column
            ><el-table-column
              prop="sourceUserName"
              label="原处理人"
            /><el-table-column
              prop="targetUserName"
              label="目标人"
            /><el-table-column
              prop="departmentName"
              label="目标部门"
            /><el-table-column
              prop="reason"
              label="原因"
              show-overflow-tooltip
            /><el-table-column prop="status" label="结果" /><el-table-column
              label="接管期限"
              ><template #default="{ row }">{{
                date(row.dueAt)
              }}</template></el-table-column
            ><el-table-column label="发起时间"
              ><template #default="{ row }">{{
                date(row.createdAt)
              }}</template></el-table-column
            ></el-table
          ></template
        >
        <template v-if="activeTab === 'cycles'"
          ><el-table :data="sections.cycles.records"
            ><el-table-column prop="cycle" label="周期" /><el-table-column
              label="开始时间"
              ><template #default="{ row }">{{
                date(row.startedAt)
              }}</template></el-table-column
            ><el-table-column label="结束时间"
              ><template #default="{ row }">{{
                date(row.endedAt)
              }}</template></el-table-column
            ><el-table-column label="自然耗时"
              ><template #default="{ row }"
                >{{
                  Math.round(
                    ((row.endedAt ? Date.parse(row.endedAt) : Date.now()) -
                      Date.parse(row.startedAt)) /
                      60000,
                  )
                }}
                分钟</template
              ></el-table-column
            ><el-table-column label="暂停时间"
              ><template #default="{ row }"
                >{{
                  Math.round(
                    (row.effectivePausedSeconds ?? row.pausedSeconds) / 60,
                  )
                }}
                分钟</template
              ></el-table-column
            ><el-table-column label="计时耗时"
              ><template #default="{ row }"
                >{{
                  Math.max(
                    0,
                    Math.round(
                      ((row.endedAt ? Date.parse(row.endedAt) : Date.now()) -
                        Date.parse(row.startedAt)) /
                        60000 -
                        (row.effectivePausedSeconds ?? row.pausedSeconds) / 60,
                    ),
                  )
                }}
                分钟</template
              ></el-table-column
            ></el-table
          ></template
        >
        <template v-if="activeTab === 'responsibilities'"
          ><el-table :data="sections.responsibilities.records"
            ><el-table-column prop="userName" label="处理人" /><el-table-column
              prop="departmentName"
              label="部门" /><el-table-column
              prop="cycle"
              label="周期" /><el-table-column label="接管时间"
              ><template #default="{ row }">{{
                date(row.startedAt)
              }}</template></el-table-column
            ><el-table-column label="离开时间"
              ><template #default="{ row }">{{
                date(row.endedAt)
              }}</template></el-table-column
            ><el-table-column label="自然耗时"
              ><template #default="{ row }"
                >{{
                  Math.round(
                    ((row.endedAt ? Date.parse(row.endedAt) : Date.now()) -
                      Date.parse(row.startedAt)) /
                      60000,
                  )
                }}
                分钟</template
              ></el-table-column
            ><el-table-column prop="endReason" label="结束原因" /></el-table
        ></template>
        <template v-if="activeTab === 'forwards'"
          ><el-empty
            v-if="!sections.forwards.records.length"
            description="暂无协作线程" />
          <div
            v-for="f in sections.forwards.records"
            :key="f.id"
            class="record"
          >
            <strong>{{ f.initiatorName }} · {{ date(f.createdAt) }}</strong
            ><el-tag>{{ threadLabels[f.status] }}</el-tag>
            <p class="text">{{ f.reason }}</p>
            <p>
              <span v-for="r in f.recipients" :key="r.userId"
                >{{ r.userName }}（{{ r.readAt ? "已读" : "未读" }}）
              </span>
            </p>
            <el-button
              @click="
                inspect(f);
                threadAttachmentPage = 1;
                loadThreadAttachments(f.id);
              "
              >查看协作</el-button
            ><el-button v-if="f.canReply" @click="show('reply', f.id)"
              >回复协作意见</el-button
            ><el-button
              v-if="f.canWithdraw"
              @click="show('forward-withdraw', f.id)"
              >撤回转发</el-button
            >
          </div>
          <el-card v-if="selectedThread" class="history"
            ><h3>协作线程 #{{ selectedThread }}</h3>
            <div v-for="r in replyRows" :key="r.id" class="record">
              <strong>{{ r.authorName }} · {{ date(r.createdAt) }}</strong>
              <p v-if="r.correctsCommentId">
                补充更正意见 #{{ r.correctsCommentId }}
              </p>
              <p class="text">{{ r.content }}</p>
              <el-button
                v-if="
                  r.authorId === me &&
                  sections.forwards.records.find((f) => f.id === selectedThread)
                    ?.canReply
                "
                link
                type="primary"
                @click="
                  show('reply', selectedThread);
                  form.correctsCommentId = r.id;
                "
                >补充更正</el-button
              >
            </div>
            <el-empty
              v-if="!replyRows.length"
              description="暂无协作回复" /><el-pagination
              v-model:current-page="replyPage"
              :page-size="20"
              :total="replyTotal"
              layout="total,prev,pager,next"
              @current-change="loadReplies(selectedThread!)" />
            <h4>协作附件</h4>
            <el-button
              v-if="
                sections.forwards.records.find((f) => f.id === selectedThread)
                  ?.canReply && !['CLOSED', 'ARCHIVED'].includes(ticket.status)
              "
              :disabled="saving"
              @click="
                attachmentThread = selectedThread;
                fileInput?.click();
              "
              >上传协作附件</el-button
            >
            <p v-for="a in threadAttachments" :key="a.id">
              <el-link type="primary" @click="download(a)">{{
                a.filename
              }}</el-link>
            </p>
            <el-pagination
              v-model:current-page="threadAttachmentPage"
              :page-size="20"
              :total="threadAttachmentTotal"
              layout="total,prev,pager,next"
              @current-change="
                loadThreadAttachments(selectedThread!)
              " /></el-card
        ></template>
        <template v-if="activeTab === 'attachments'"
          ><el-button
            v-if="
              ticket.allowedActions.some((a) =>
                ['edit', 'supplement', 'processing-comment'].includes(a),
              )
            "
            :disabled="saving"
            @click="
              attachmentThread = undefined;
              fileInput?.click();
            "
            >上传附件</el-button
          >
          <p>每次最多10个附件，每个不超过20MB。</p>
          <el-table :data="sections.attachments.records"
            ><el-table-column label="文件"
              ><template #default="{ row }"
                ><el-link type="primary" @click="download(row)">{{
                  row.filename
                }}</el-link></template
              ></el-table-column
            ><el-table-column label="大小"
              ><template #default="{ row }"
                >{{ Math.ceil(row.size / 1024) }} KB</template
              ></el-table-column
            ><el-table-column label="上传时间"
              ><template #default="{ row }">{{
                date(row.createdAt)
              }}</template></el-table-column
            ></el-table
          ></template
        >
        <el-pagination
          v-model:current-page="sections[activeTab].page"
          :page-size="20"
          :total="sections[activeTab].total"
          layout="total,prev,pager,next"
          @current-change="loadSection(activeTab).catch(error)"
        /> </el-card
      ><input
        ref="fileInput"
        type="file"
        hidden
        multiple
        accept=".pdf,.txt,.csv,.png,.jpg,.jpeg,.gif,.docx,.xlsx,.pptx,.log,.zip"
        @change="upload"
      />
    </template>
    <el-dialog
      v-model="open"
      :title="actions[operation]"
      width="650px"
      :close-on-click-modal="!saving"
      ><el-form label-width="110px" @submit.prevent>
        <template v-if="basics"
          ><el-form-item label="标题" required
            ><el-input v-model="form.title" maxlength="200" /></el-form-item
          ><el-form-item label="描述" required
            ><el-input
              v-model="form.description"
              type="textarea"
              :rows="4"
              maxlength="10000" /></el-form-item
          ><el-form-item label="分类"
            ><el-select v-model="form.category"
              ><el-option
                v-for="c in options.categories"
                :key="c.code"
                :value="c.code"
                :label="c.label" /></el-select
          ></el-form-item>
          <el-form-item label="影响度"
            ><el-select v-model="form.impact"
              ><el-option label="广" value="WIDE" /><el-option
                label="局部"
                value="PARTIAL" /><el-option
                label="单点"
                value="SINGLE" /></el-select></el-form-item
          ><el-form-item label="紧急度"
            ><el-select v-model="form.urgency"
              ><el-option label="高" value="HIGH" /><el-option
                label="中"
                value="MEDIUM" /><el-option
                label="低"
                value="LOW" /></el-select></el-form-item
          ><el-form-item label="优先级"
            ><el-select v-model="form.priority"
              ><el-option
                v-for="p in ['P1', 'P2', 'P3', 'P4']"
                :key="p"
                :value="p" /></el-select></el-form-item
          ><el-form-item label="调整原因"
            ><el-input v-model="form.reason" type="textarea" /></el-form-item
          ><el-form-item label="发生时间"
            ><el-date-picker
              v-model="form.occurredAt"
              type="datetime" /></el-form-item
        ></template>
        <template v-if="peopleAction"
          ><el-alert
            v-if="operation === 'forward'"
            title="接收人可查看并回复；责任人不变。"
            :closable="false"
          /><el-alert
            v-if="operation === 'transfer'"
            title="目标接受前仍由当前处理人负责。"
            :closable="false"
          /><el-alert
            v-if="operation === 'reopen'"
            title="不选择新处理人时恢复历史处理人；选择新处理人需具备服务台分派权限。"
            :closable="false"
          />
          <el-form-item
            v-if="operation !== 'forward'"
            label="目标部门"
            :required="operation !== 'reopen'"
            ><el-select
              v-model="form.departmentId"
              clearable
              filterable
              @change="
                form.targetUserId = '';
                findRecipients();
              "
              ><el-option
                v-for="d in options.departments"
                :key="d.id"
                :label="d.name"
                :value="d.id" /></el-select
          ></el-form-item>
          <el-form-item label="角色筛选"
            ><el-select
              v-model="form.roles"
              multiple
              clearable
              @change="findRecipients()"
              ><el-option
                v-for="r in options.roles"
                :key="r.code"
                :label="r.name"
                :value="r.code" /></el-select
          ></el-form-item>
          <el-form-item
            :label="operation === 'forward' ? '接收人' : '目标处理人'"
            :required="operation !== 'reopen'"
            ><el-select
              v-if="operation === 'forward'"
              v-model="form.recipientIds"
              multiple
              filterable
              remote
              :remote-method="findRecipients"
              style="width: 100%"
              ><el-option
                v-for="p in recipients"
                :key="p.id"
                :value="p.id"
                :label="`${p.nickname} (${p.username}) · ${p.deptName}`" /></el-select
            ><el-select
              v-else
              v-model="form.targetUserId"
              filterable
              clearable
              remote
              :remote-method="findRecipients"
              style="width: 100%"
              ><el-option
                v-for="p in recipients"
                :key="p.id"
                :value="p.id"
                :label="`${p.nickname} (${p.username}) · ${p.deptName}`" /></el-select
            ><el-button
              v-if="recipients.length < recipientTotal"
              link
              @click="
                recipientPage++;
                findRecipients(recipientKeyword, true);
              "
              >加载更多人员</el-button
            ></el-form-item
          ></template
        >
        <el-form-item v-if="reasonRequired" label="原因/说明" required
          ><el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            maxlength="10000"
        /></el-form-item>
        <template v-if="operation === 'transfer'"
          ><el-form-item label="当前进展" required
            ><el-input v-model="form.progress" type="textarea" /></el-form-item
          ><el-form-item label="未完成事项" required
            ><el-input v-model="form.remaining" type="textarea" /></el-form-item
        ></template>
        <template
          v-if="
            ['supplement', 'processing-comment', 'reply'].includes(operation)
          "
          ><el-alert
            v-if="operation === 'reply'"
            title="这是协作意见，不代表接管或解决。"
            :closable="false" /><el-form-item label="意见内容" required
            ><el-input
              v-model="form.content"
              type="textarea"
              :rows="5"
              maxlength="10000" /></el-form-item
        ></template>
        <template v-if="operation === 'resolve'"
          ><el-form-item
            v-for="[k, label] in [
              ['analysis', '原因分析'],
              ['measures', '采取措施'],
              ['result', '处理结果'],
              ['verification', '验证说明'],
            ]"
            :key="k"
            :label="label"
            required
            ><el-input
              v-model="form[k]"
              type="textarea"
              :rows="2"
              maxlength="10000" /></el-form-item
        ></template>
        <template v-if="operation === 'suspend'"
          ><el-form-item label="外部条件"
            ><el-checkbox v-model="form.externalCondition"
              >等待第三方或必要外部条件</el-checkbox
            ></el-form-item
          ><el-form-item label="预计恢复" required
            ><el-date-picker
              v-model="form.expectedResumeAt"
              type="datetime" /></el-form-item
        ></template>
        <el-form-item v-if="operation === 'return-decision'" label="审批结果"
          ><el-radio-group v-model="form.approved"
            ><el-radio :value="true">同意退回</el-radio
            ><el-radio :value="false">拒绝退回</el-radio></el-radio-group
          ></el-form-item
        >
        <p v-if="operation === 'accept'">
          接管后您需要提交处理意见和最终处理结果，或通过有效转交移交责任。
        </p>
        <p v-if="operation === 'submit'">
          提交后进入待分配，开始计算响应和解决时限。
        </p>
        <el-form-item v-if="form.correctsCommentId" label="更正原意见"
          >#{{ form.correctsCommentId }}（原记录保持不变）</el-form-item
        >
        <el-form-item
          v-if="
            ['supplement', 'processing-comment', 'reply', 'resolve'].includes(
              operation,
            )
          "
          label="关联附件"
          ><el-select
            v-model="form.attachmentIds"
            multiple
            clearable
            style="width: 100%"
            ><el-option
              v-for="a in (operation === 'reply'
                ? threadAttachments
                : sections.attachments.records
              ).filter((a) => !a.commentId && a.uploaderId === me)"
              :key="a.id"
              :value="a.id"
              :label="a.filename" /></el-select
          ><el-button
            link
            type="primary"
            :disabled="saving"
            @click="
              attachmentThread = operation === 'reply' ? thread : undefined;
              fileInput?.click();
            "
            >上传新附件</el-button
          ></el-form-item
        > </el-form
      ><template #footer
        ><el-button :disabled="saving" @click="open = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save()"
          >确认</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
<style scoped>
.heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 12px;
}
h2 {
  margin: 10px 0;
}
.buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 20px 0;
}
.buttons .el-button {
  margin: 0;
}
.history {
  margin-top: 18px;
}
.text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.record {
  padding: 16px 0;
  border-bottom: 1px solid #e2e8f0;
}
.record small {
  display: block;
  color: #64748b;
  margin-top: 8px;
}
.record .el-tag {
  margin-left: 12px;
}
.el-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
.el-alert {
  margin-bottom: 16px;
}
</style>
