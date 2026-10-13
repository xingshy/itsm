<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { fetchDeptEmployees, type LeaderOption } from '../api/dept';
const props = defineProps<{ deptId: string }>();
const employees = ref<LeaderOption[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
let requestId = 0;
async function load(): Promise<void> {
  const request = ++requestId;
  employees.value = [];
  loading.value = true;
  try {
    const result = await fetchDeptEmployees(props.deptId, page.value);
    if (request !== requestId) return;
    employees.value = result.records;
    total.value = result.total;
  } catch (e) {
    if (request === requestId) { total.value = 0; ElMessage.error((e as Error).message); }
  } finally {
    if (request === requestId) loading.value = false;
  }
}
watch(() => props.deptId, () => { page.value = 1; total.value = 0; void load(); }, { immediate: true });
</script>
<template>
  <div>
    <div class="employee-summary">直属员工共 {{ total }} 人</div>
    <el-table :data="employees" v-loading="loading" stripe empty-text="暂无直属员工" max-height="360">
      <el-table-column prop="nickname" label="姓名" min-width="100" />
      <el-table-column prop="employeeNo" label="工号" min-width="120" />
      <el-table-column prop="username" label="账号" min-width="140" />
      <el-table-column prop="email" label="邮箱" min-width="200" />
      <el-table-column prop="phone" label="手机号" min-width="140" />
    </el-table>
    <el-pagination v-if="total > 20" v-model:current-page="page" :page-size="20" :total="total"
      layout="total, prev, pager, next" @current-change="load" style="margin-top: 12px" />
  </div>
</template>
<style scoped>
.employee-summary { margin-bottom: 10px; color: #606266; }
</style>
