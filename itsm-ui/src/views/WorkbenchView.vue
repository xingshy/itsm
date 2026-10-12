<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import http from '../api/http';
import {incidentApi} from '../api/incident';
const incidentCounts = ref<Record<string,number>>({});
const incidentEnabled = ref(false);
onMounted(async () => { try { const me = await http.get<never,{roles:string[];perms:string[]}>('/auth/me'); incidentEnabled.value = me.roles.includes('ADMIN') || me.perms.includes('itsm:incident:list'); if (incidentEnabled.value) incidentCounts.value = await incidentApi.counters(); } catch { incidentEnabled.value = false; } });
import { useRouter } from 'vue-router';
import {
  ticketStore, TYPE_LABEL, TYPE_TAG,
  STATUS_LABEL, STATUS_TAG
} from '../api/ticket';
import type { Ticket } from '../api/ticket';

const router = useRouter();

// 原型:统计来自内存工单仓库;后端就绪后改调 GET /api/v1/dashboard/overview
const overview = computed(() => ticketStore.overview());

/** 待受理工单(前 5 条) */
const pendingRows = computed<Ticket[]>(() =>
  ticketStore.list({ status: 'PENDING' }).slice(0, 5));

function fmtTime(v: string): string {
  return new Date(v).toLocaleString('zh-CN', { hour12: false });
}
</script>

<template>
  <div>
    <el-card v-if="incidentEnabled" style="margin-bottom:16px">
      <template #header><el-link type="primary" @click="router.push('/incidents')">事件待办</el-link></template>
      <el-row :gutter="16"><el-col v-for="[key,label,tab] in [['takeover','待我接管','takeover'],['processing','待我处理','processing'],['collaborationUnread','协作未读','forwarded'],['confirm','待确认解决','created']]" :key="key" :span="6">
        <el-link @click="router.push({path:'/incidents',query:{tab}})"><el-statistic :title="label" :value="incidentCounts[key] || 0" /></el-link>
      </el-col></el-row>
    </el-card>
    <!-- 统计卡 -->
    <el-row :gutter="16">
      <el-col :span="5">
        <el-card shadow="never" class="stat-card">
          <el-statistic title="今日新建" :value="overview.todayNew" />
        </el-card>
      </el-col>
      <el-col :span="5">
        <el-card shadow="never" class="stat-card">
          <el-statistic title="待受理" :value="overview.pending" :value-style="{ color: '#f59e0b' }" />
        </el-card>
      </el-col>
      <el-col :span="5">
        <el-card shadow="never" class="stat-card">
          <el-statistic title="处理中" :value="overview.processing" :value-style="{ color: '#3b82f6' }" />
        </el-card>
      </el-col>
      <el-col :span="5">
        <el-card shadow="never" class="stat-card">
          <el-statistic title="已超期" :value="overview.overdue" :value-style="{ color: '#ef4444' }" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="never" class="stat-card">
          <el-statistic title="本周解决率" :value="overview.resolveRate" suffix="%" :value-style="{ color: '#10b981' }" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <!-- 待受理列表 -->
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center">
              <span>待受理工单</span>
              <el-button link type="primary" @click="router.push('/tickets')">
                进入工单管理 →
              </el-button>
            </div>
          </template>
          <el-table :data="pendingRows" size="small">
            <el-table-column label="单号" width="170">
              <template #default="{ row }">
                <el-link type="primary" @click="router.push(`/tickets/${row.id}`)">{{ row.no }}</el-link>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="TYPE_TAG[row.type as keyof typeof TYPE_TAG]" effect="plain" size="small">
                  {{ TYPE_LABEL[row.type as keyof typeof TYPE_LABEL] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag :type="STATUS_TAG[row.status as keyof typeof STATUS_TAG]" size="small">
                  {{ STATUS_LABEL[row.status as keyof typeof STATUS_LABEL] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="pendingRows.length === 0" description="没有待受理工单,干得漂亮 🎉" :image-size="80" />
        </el-card>
      </el-col>

      <!-- 快捷入口 -->
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>快捷操作</template>
          <div class="quick-grid">
            <el-button size="large" type="primary" plain @click="router.push('/tickets/create')">
              ＋ 提交工单
            </el-button>
            <el-button size="large" plain @click="router.push('/my-tickets/todo')">
              📋 我的工单
            </el-button>
            <el-button size="large" plain disabled>
              📚 知识库(V1.1)
            </el-button>
            <el-button size="large" plain disabled>
              📊 报表中心(M3)
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.stat-card :deep(.el-statistic__head) {
  font-size: 13px;
}

.quick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.quick-grid .el-button {
  margin: 0;
  width: 100%;
}
</style>

