<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import http, {clearToken} from '../api/http';
import IncidentNotificationCenter from '../components/incident/IncidentNotificationCenter.vue';
const canViewDept = ref(false);
const canViewIncident = ref(false);
const currentName = ref("用户");
onMounted(async () => {
  try {
    const me = await http.get<never, { roles: string[]; perms: string[]; nickname:string; username:string }>('/auth/me');
    currentName.value = me.nickname || me.username;
    canViewDept.value = me.roles.includes('ADMIN') || me.perms.includes('sys:dept:list');
    canViewIncident.value = me.roles.includes('ADMIN') || me.perms.includes('itsm:incident:list');
  } catch { canViewDept.value = false; }
});
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();

/** 顶栏面包屑 */
const breadcrumb = computed(() => {
  const title = route.meta.title as string | undefined;
  if (route.path.startsWith('/incidents') || route.path.startsWith('/tickets')) {
    return ['工单管理', title || '工单列表'];
  }
  return title ? [title] : ['工作台'];
});

function onLogout(): void {
  // M1 打通后:调用 POST /api/v1/auth/logout 并清除本地 token
  clearToken();
  router.push('/login');
}
</script>

<template>
  <el-container class="layout">
    <!-- 侧边导航(科技蓝) -->
    <el-aside width="220px" class="aside">
      <div class="logo">
        <div class="logo-mark">IT</div>
        <span class="logo-text">IT服务管理平台</span>
      </div>
      <el-menu router class="menu" :default-active="route.path" :default-openeds="['ticket-management']">
        <el-menu-item index="/workbench">
          <el-icon><Odometer /></el-icon>
          <span>工作台</span>
        </el-menu-item>

        <el-sub-menu index="ticket-management">
          <template #title>
            <el-icon><Tickets /></el-icon>
            <span>工单管理</span>
          </template>
          <el-menu-item index="/tickets">工单列表</el-menu-item>
          <el-menu-item v-if="canViewIncident" index="/incidents">事件管理</el-menu-item>
        </el-sub-menu>

        <el-sub-menu index="/my-tickets">
          <template #title>
            <el-icon><Document /></el-icon>
            <span>我的工单</span>
          </template>
          <el-menu-item index="/my-tickets/drafts">我的草稿</el-menu-item>
          <el-menu-item index="/my-tickets/todo">我的待办</el-menu-item>
          <el-menu-item index="/my-tickets/submitted">我提交的</el-menu-item>
          <el-menu-item index="/my-tickets/participated">我参与的</el-menu-item>
        </el-sub-menu>

        <el-sub-menu index="system">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span>系统管理</span>
          </template>
          <el-menu-item index="/system/user">用户管理</el-menu-item>
          <el-menu-item v-if="canViewDept" index="/system/dept">部门管理</el-menu-item>
          <el-menu-item index="/system/role">角色管理</el-menu-item>
          <el-menu-item index="/system/menu">菜单管理</el-menu-item>
          <el-menu-item index="/system/process">流程管理</el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/workbench' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item v-for="item in breadcrumb" :key="item">{{ item }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div style="display:flex;align-items:center;gap:24px"><IncidentNotificationCenter v-if="canViewIncident"/><el-dropdown>
          <span class="user">
            <el-avatar :size="28" style="margin-right: 8px">{{ currentName.slice(0, 1).toUpperCase() }}</el-avatar>
            {{ currentName }}
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>个人设置</el-dropdown-item>
              <el-dropdown-item divided @click="onLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown></div>
      </el-header>

      <!-- 主内容区 -->
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

/* 科技蓝侧边栏 */
.aside {
  background: linear-gradient(180deg, #0a2f73 0%, #0f3f9d 55%, #0a2f73 100%);
  box-shadow: 1px 0 0 rgba(77, 159, 255, 0.35);
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.14);
}

.logo-mark {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-weight: 700;
  font-size: 13px;
  color: #fff;
  background: linear-gradient(135deg, #1677ff 0%, #00c8ff 100%);
  box-shadow: 0 0 14px rgba(0, 170, 255, 0.55);
}

.logo-text {
  color: #eaf4ff;
  font-weight: 600;
  font-size: 15px;
  letter-spacing: 1px;
  white-space: nowrap;
}

.menu {
  border-right: none;
  background: transparent;
  padding: 8px;
  --el-menu-bg-color: transparent;
}

.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  color: #cfe0ff;
  border-radius: 8px;
  margin: 4px 0;
  height: 44px;
  transition: all 0.2s;
}

.menu :deep(.el-menu-item:hover),
.menu :deep(.el-sub-menu__title:hover) {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.12);
}

/* 选中项:亮蓝渐变 + 辉光 */
.menu :deep(.el-menu-item.is-active) {
  color: #ffffff;
  background: linear-gradient(90deg, #1677ff 0%, #00c8ff 100%);
  box-shadow: 0 0 16px rgba(0, 170, 255, 0.5);
}

.menu :deep(.el-menu--inline) {
  background: rgba(4, 18, 48, 0.35);
  border-radius: 10px;
  margin: 2px 0;
}

.menu :deep(.el-menu--inline .el-menu-item) {
  padding-left: 44px !important;
}

.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
}

.user {
  display: flex;
  align-items: center;
  cursor: pointer;
  color: #344054;
}

.main {
  background: #f5f7fa;
  overflow: auto;
}
</style>

