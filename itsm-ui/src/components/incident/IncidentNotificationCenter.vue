<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import http from "../../api/http";
import { date, type Page } from "../../api/incident";
const router = useRouter(),
  open = ref(false),
  rows = ref<Record<string, any>[]>([]),
  total = ref(0),
  page = ref(1),
  unread = ref(0),
  onlyUnread = ref(false),
  loading = ref(false);
let timer: ReturnType<typeof setInterval> | undefined;
const labels: Record<string, string> = {
  REMINDER: "服务台催办",
  TAKEOVER: "待接管",
  COLLABORATION: "协作邀请",
  REPLY: "协作回复",
  accept: "已接管",
  resolve: "待确认解决",
  close: "已关闭",
  archive: "已归档",
  submit: "待分配",
  PENDING_ASSIGNMENT: "待分配",
  "return-request": "退回审批",
  "reopen-request": "重开申请",
  TAKEOVER_OVERDUE: "接管超时",
  RESPONSE_OVERDUE: "响应超时",
  RESOLUTION_OVERDUE: "处理超时",
  RESUME_OVERDUE: "挂起超过预计恢复时间",
};
async function refreshCount() {
  try {
    const result = await http.get<never, Page<Record<string, any>>>(
      "/incidents/notifications",
      { params: { unread: true, size: 1 } },
    );
    unread.value = result.total;
  } catch {}
}
async function load() {
  loading.value = true;
  try {
    const result = await http.get<never, Page<Record<string, any>>>(
      "/incidents/notifications",
      {
        params: {
          page: page.value,
          size: 20,
          unread: onlyUnread.value || undefined,
        },
      },
    );
    rows.value = result.records;
    total.value = result.total;
    await refreshCount();
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    loading.value = false;
  }
}
async function visit(n: Record<string, any>) {
  try {
    await http.post(`/incidents/notifications/${n.id}/read`);
    open.value = false;
    await refreshCount();
    router.push("/incidents/" + n.ticketId);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}
onMounted(() => {
  refreshCount();
  timer = setInterval(refreshCount, 60000);
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>
<template>
  <el-badge :value="unread" :hidden="!unread" :max="99"
    ><el-button
      @click="
        open = true;
        load();
      "
      >事件通知</el-button
    ></el-badge
  ><el-drawer v-model="open" title="事件通知" size="440px"
    ><el-checkbox
      v-model="onlyUnread"
      @change="
        page = 1;
        load();
      "
      >仅未读</el-checkbox
    >
    <div v-loading="loading">
      <el-empty v-if="!rows.length" description="暂无通知" />
      <div v-for="n in rows" :key="n.id" class="notification" @click="visit(n)">
        <strong
          >{{ labels[n.kind] || "事件进展" }}
          <el-tag v-if="!n.readAt" size="small">未读</el-tag></strong
        >
        <p>{{ n.ticketNo }} · {{ n.title }}</p>
        <small>{{ date(n.createdAt) }}</small>
      </div>
    </div>
    <el-pagination
      v-model:current-page="page"
      :page-size="20"
      :total="total"
      layout="total,prev,pager,next"
      @current-change="load"
  /></el-drawer>
</template>
<style scoped>
.notification {
  cursor: pointer;
  padding: 18px 0;
  border-bottom: 1px solid #e2e8f0;
}
.notification:hover {
  color: #2563eb;
}
.notification p {
  overflow-wrap: anywhere;
}
small {
  color: #64748b;
}
.el-pagination {
  margin-top: 16px;
}
</style>
