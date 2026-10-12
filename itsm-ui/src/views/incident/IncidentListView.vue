<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import http from "../../api/http";
import IncidentPersonSelect from "../../components/incident/IncidentPersonSelect.vue";
import {
  incidentApi,
  statuses,
  date,
  overdue,
  type Incident,
  type Options,
} from "../../api/incident";
const router = useRouter();
const route = useRoute();
const loading = ref(false);
const records = ref<Incident[]>([]);
const total = ref(0);
const page = ref(1);
const tab = ref(String(route.query.tab || "all"));
const canCreate = ref(false);
const options = ref<Options>({ categories: [], departments: [], roles: [] });
const range = ref<string[]>([]);
const counts = ref<Record<string, number>>({});
const filters = reactive({
  keyword: "",
  status: "",
  category: "",
  priority: "",
  creatorId: "",
  departmentId: "",
  ownerId: "",
  overdue: false,
});
const tabs = [
  ["all", "全部"],
  ["created", "我创建的"],
  ["takeover", "待我接管"],
  ["processing", "我处理的"],
  ["forwarded", "转发给我的"],
  ["history", "我曾处理的"],
  ["archived", "已归档"],
];
let applied: Record<string, unknown> = {};
let request = 0;
async function load() {
  const seq = ++request;
  loading.value = true;
  try {
    const result = await incidentApi.list({
      ...applied,
      tab: tab.value,
      page: page.value,
      size: 20,
    });
    if (seq === request) {
      records.value = result.records;
      total.value = result.total;
    }
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    if (seq === request) loading.value = false;
  }
}
function query() {
  applied = {
    ...filters,
    overdue: filters.overdue || undefined,
    from: range.value[0],
    to: range.value[1],
  };
  page.value = 1;
  load();
}
function reset() {
  Object.assign(filters, {
    keyword: "",
    status: "",
    category: "",
    priority: "",
    creatorId: "",
    departmentId: "",
    ownerId: "",
    overdue: false,
  });
  range.value = [];
  query();
}
onMounted(async () => {
  try {
    const [o, me, c] = await Promise.all([
      incidentApi.options(),
      http.get<never, { roles: string[]; perms: string[] }>("/auth/me"),
      incidentApi.counters(),
    ]);
    options.value = o;
    counts.value = c;
    canCreate.value =
      me.roles.includes("ADMIN") || me.perms.includes("itsm:incident:create");
    await load();
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
});
</script>
<template>
  <el-card
    ><div class="heading">
      <div>
        <h2>事件管理</h2>
        <p>跟进故障处理、协作和责任移交</p>
      </div>
      <el-button
        v-if="canCreate"
        type="primary"
        @click="router.push('/incidents/create')"
        >新增事件</el-button
      >
    </div>
    <el-form inline @submit.prevent="query"
      ><el-form-item label="关键词"
        ><el-input
          v-model="filters.keyword"
          clearable
          placeholder="编号、标题、描述"
          @keyup.enter="query"
      /></el-form-item>
      <el-form-item label="状态"
        ><el-select v-model="filters.status" clearable style="width: 150px"
          ><el-option
            v-for="(label, key) in statuses"
            :key="key"
            :label="label"
            :value="key" /></el-select
      ></el-form-item>
      <el-form-item label="分类"
        ><el-select v-model="filters.category" clearable style="width: 130px"
          ><el-option
            v-for="c in options.categories"
            :key="c.code"
            :value="c.code"
            :label="c.label" /></el-select
      ></el-form-item>
      <el-form-item label="优先级"
        ><el-select v-model="filters.priority" clearable style="width: 110px"
          ><el-option
            v-for="p in ['P1', 'P2', 'P3', 'P4']"
            :key="p"
            :value="p" /></el-select
      ></el-form-item>
      <el-form-item label="处理部门"
        ><el-select
          v-model="filters.departmentId"
          clearable
          filterable
          style="width: 180px"
          ><el-option
            v-for="d in options.departments"
            :key="d.id"
            :label="d.name"
            :value="d.id" /></el-select
      ></el-form-item>
      <el-form-item label="创建时间"
        ><el-date-picker
          v-model="range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ssZ"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
      /></el-form-item>
      <el-form-item label="申请人"
        ><IncidentPersonSelect
          v-model="filters.creatorId"
          style="width: 230px" /></el-form-item
      ><el-form-item label="处理人"
        ><IncidentPersonSelect v-model="filters.ownerId" style="width: 230px"
      /></el-form-item>
      <el-form-item
        ><el-checkbox v-model="filters.overdue"
          >仅超时</el-checkbox
        ></el-form-item
      ><el-form-item
        ><el-button type="primary" @click="query">查询</el-button
        ><el-button @click="reset">重置</el-button></el-form-item
      ></el-form
    >
    <el-tabs
      v-model="tab"
      @tab-change="
        page = 1;
        load();
      "
      ><el-tab-pane
        v-for="[key, label] in tabs"
        :key="key"
        :name="key"
        :label="label + (counts[key] ? ' (' + counts[key] + ')' : '')"
    /></el-tabs>
    <el-table
      v-loading="loading"
      :data="records"
      stripe
      @row-dblclick="(r: Incident) => router.push('/incidents/' + r.id)"
    >
      <el-table-column label="编号" min-width="190"
        ><template #default="{ row }"
          ><el-link
            type="primary"
            @click="router.push('/incidents/' + row.id)"
            >{{ row.ticketNo || "草稿 #" + row.id }}</el-link
          ></template
        ></el-table-column
      >
      <el-table-column
        prop="title"
        label="标题"
        min-width="220"
        show-overflow-tooltip
      /><el-table-column label="状态" width="110"
        ><template #default="{ row }"
          ><el-tag>{{ statuses[row.status] }}</el-tag></template
        ></el-table-column
      >
      <el-table-column
        prop="priority"
        label="优先级"
        width="85"
      /><el-table-column
        prop="creatorName"
        label="创建人"
        width="110"
      /><el-table-column prop="departmentName" label="处理部门" width="130" />
      <el-table-column
        prop="ownerName"
        label="当前处理人"
        width="120"
      /><el-table-column prop="pendingUserName" label="待接管人" width="120" />
      <el-table-column label="创建时间" width="180"
        ><template #default="{ row }">{{
          date(row.createdAt)
        }}</template></el-table-column
      ><el-table-column label="更新时间" width="180"
        ><template #default="{ row }">{{
          date(row.updatedAt)
        }}</template></el-table-column
      >
      <el-table-column label="时限" width="180"
        ><template #default="{ row }"
          ><el-tag v-if="overdue(row)" type="danger">已超时</el-tag
          ><span v-else>{{ date(row.resolutionDueAt) }}</span></template
        ></el-table-column
      ></el-table
    >
    <el-pagination
      v-model:current-page="page"
      :page-size="20"
      :total="total"
      layout="total,prev,pager,next"
      @current-change="load"
    />
  </el-card>
</template>
<style scoped>
.heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
h2 {
  margin: 0;
}
p {
  color: #64748b;
}
.el-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
