<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createUser, deleteUsers, fetchDeptTree, fetchRoles, pageUsers,
  resetUserPassword, updateUser,
  type DeptNode, type UserVO
} from '../../api/user';

// ===== 选项数据(真实接口) =====
const deptTree = ref<DeptNode[]>([]);
const roleOptions = ref<{ id: number; name: string }[]>([]);

async function loadOptions(): Promise<void> {
  try {
    const [depts, roles] = await Promise.all([fetchDeptTree(), fetchRoles()]);
    deptTree.value = depts;
    roleOptions.value = roles.filter((r) => r.status === 1).map((r) => ({ id: r.id, name: r.name }));
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载部门/角色选项失败');
  }
}

function toTreeSelectOptions(nodes: DeptNode[]): { value: string; label: string; children?: unknown[] }[] {
  return nodes.map((n) => ({
    value: n.id,
    label: n.name,
    children: (n.children ?? []).length ? toTreeSelectOptions(n.children ?? []) : undefined
  }));
}
const deptOptions = computed(() => toTreeSelectOptions(deptTree.value));

function fmt(v: string): string {
  return v ? v.replace('T', ' ').slice(0, 16) : '-';
}

// ===== 列表与筛选(分页) =====
const loading = ref(false);
const filter = reactive<{ keyword: string; deptId?: string; status?: 0 | 1 }>({ keyword: '' });
const pageNum = ref(1);
const pageSize = ref(10);
const total = ref(0);
const rows = ref<UserVO[]>([]);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const page = await pageUsers({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: filter.keyword.trim() || undefined,
      deptId: filter.deptId,
      status: filter.status
    });
    rows.value = page.records;
    total.value = page.total;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载用户列表失败');
  } finally {
    loading.value = false;
  }
}

function search(): void {
  pageNum.value = 1;
  load();
}
function resetFilter(): void {
  filter.keyword = '';
  filter.deptId = undefined;
  filter.status = undefined;
  search();
}
function onSizeChange(): void {
  pageNum.value = 1;
  load();
}
onMounted(() => {
  loadOptions();
  load();
});

// ===== 新增 / 编辑 =====
const dialog = reactive({ visible: false, isEdit: false, editingId: 0 });
const form = reactive<{
  username: string; nickname: string; password: string;
  deptId?: string; email: string; phone: string;
  status: 0 | 1; roleIds: number[];
}>({ username: '', nickname: '', password: '', deptId: undefined, email: '', phone: '', status: 1, roleIds: [] });
const formRef = ref();
const submitting = ref(false);

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{3,32}$/, message: '3-32 位字母/数字/下划线', trigger: 'blur' }],
  nickname: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, max: 32, message: '长度 6-32 位', trigger: 'blur' }],
  deptId: [{ required: true, message: '请选择所属部门', trigger: 'change' }]
};

function openCreate(): void {
  loadOptions();
  dialog.isEdit = false;
  dialog.editingId = 0;
  Object.assign(form, { username: '', nickname: '', password: '', deptId: undefined, email: '', phone: '', status: 1, roleIds: [] });
  dialog.visible = true;
}
function openEdit(row: UserVO): void {
  loadOptions();
  dialog.isEdit = true;
  dialog.editingId = row.id;
  Object.assign(form, {
    username: row.username, nickname: row.nickname, password: '',
    deptId: row.deptId ?? undefined, email: row.email ?? '', phone: row.phone ?? '',
    status: row.status, roleIds: [...row.roleIds]
  });
  dialog.visible = true;
}
function submit(): void {
  formRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      return;
    }
    submitting.value = true;
    try {
      if (dialog.isEdit) {
        await updateUser(dialog.editingId, {
          nickname: form.nickname.trim(), deptId: form.deptId ?? null,
          email: form.email.trim() || null, phone: form.phone.trim() || null,
          status: form.status, roleIds: [...form.roleIds]
        });
        ElMessage.success('已保存');
      } else {
        await createUser({
          username: form.username.trim(), password: form.password, nickname: form.nickname.trim(),
          deptId: form.deptId ?? null, email: form.email.trim() || null,
          phone: form.phone.trim() || null, status: form.status, roleIds: [...form.roleIds]
        });
        ElMessage.success('已创建');
      }
      dialog.visible = false;
      load();
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      submitting.value = false;
    }
  });
}

// ===== 启停 / 重置密码 / 删除 =====
async function onToggle(row: UserVO, e: string | number | boolean): Promise<void> {
  const next = (e === 1 || e === '1' || e === true ? 1 : 0) as 0 | 1;
  try {
    await updateUser(row.id, {
      nickname: row.nickname, deptId: row.deptId, email: row.email,
      phone: row.phone, status: next, roleIds: row.roleIds
    });
    row.status = next;
    ElMessage.success(next === 1 ? '已启用' : '已停用');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

const pwdDialog = reactive({ visible: false, userId: 0, username: '', password: '' });
function openResetPwd(row: UserVO): void {
  pwdDialog.userId = row.id;
  pwdDialog.username = row.username;
  pwdDialog.password = '';
  pwdDialog.visible = true;
}
async function submitResetPwd(): Promise<void> {
  if (pwdDialog.password.length < 6) {
    ElMessage.warning('密码长度至少 6 位');
    return;
  }
  try {
    await resetUserPassword(pwdDialog.userId, pwdDialog.password);
    pwdDialog.visible = false;
    ElMessage.success(`已重置 ${pwdDialog.username} 的密码`);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重置失败');
  }
}

/**
 * 删除人员:后端为逻辑删除(仅标记 sys_user.deleted=true),
 * 不物理删除人员记录,数据保留、支持恢复;已删除用户不可登录、列表不可见。
 */
function onDelete(row: UserVO): void {
  ElMessageBox.confirm(
    `确认删除用户「${row.nickname}(${row.username})」?删除仅做逻辑标记,记录将保留以便恢复,该用户将无法登录。`,
    '删除确认', { type: 'warning' })
    .then(async () => {
      try {
        await deleteUsers([row.id]);
        ElMessage.success('已删除(数据保留)');
        load();
      } catch (e) {
        ElMessage.error(e instanceof Error ? e.message : '删除失败');
      }
    })
    .catch(() => undefined);
}
</script>

<template>
  <el-card shadow="never">
    <!-- 筛选区 -->
    <div class="toolbar">
      <el-input v-model="filter.keyword" placeholder="用户名 / 姓名" clearable style="width: 200px" @keyup.enter="search" />
      <el-tree-select v-model="filter.deptId" :data="deptOptions" filterable placeholder="所属部门" clearable check-strictly style="width: 180px" />
      <el-select v-model="filter.status" placeholder="状态" clearable style="width: 120px">
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新增用户</el-button>
    </div>

    <!-- 列表 -->
    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="employeeNo" label="工号" min-width="120" />
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column prop="nickname" label="姓名" min-width="110" />
      <el-table-column label="部门" min-width="130">
        <template #default="{ row }">{{ row.deptName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="角色" min-width="180">
        <template #default="{ row }">
          <el-tag v-for="name in row.roleNames" :key="name" size="small" style="margin-right: 4px">{{ name }}</el-tag>
          <span v-if="!row.roleNames.length" class="muted">未分配</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.status" :active-value="1" :inactive-value="0" @change="onToggle(row, $event)" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" @click="openResetPwd(row)">重置密码</el-button>
          <el-button link type="danger" :disabled="row.username === 'admin'" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
    <div class="muted tip">删除为逻辑删除:仅标记 deleted,不物理删除人员记录,可由管理员在数据库中恢复;内置 admin 不可删除</div>
  </el-card>

  <!-- 新增 / 编辑 -->
  <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑用户' : '新增用户'" width="560px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" :disabled="dialog.isEdit" placeholder="登录账号" />
      </el-form-item>
      <el-form-item label="姓名" prop="nickname">
        <el-input v-model="form.nickname" />
      </el-form-item>
      <el-form-item v-if="!dialog.isEdit" label="初始密码" prop="password">
        <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
      </el-form-item>
      <el-form-item label="所属部门" prop="deptId">
        <el-tree-select v-model="form.deptId" :data="deptOptions" filterable check-strictly style="width: 100%" />
      </el-form-item>
      <el-form-item label="邮箱">
        <el-input v-model="form.email" />
      </el-form-item>
      <el-form-item label="手机号">
        <el-input v-model="form.phone" />
      </el-form-item>
      <el-form-item label="角色">
        <el-select v-model="form.roleIds" multiple placeholder="可多选" style="width: 100%">
          <el-option v-for="r in roleOptions" :key="r.id" :label="r.name" :value="r.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-radio-group v-model="form.status">
          <el-radio :value="1">启用</el-radio>
          <el-radio :value="0">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
    </template>
  </el-dialog>

  <!-- 重置密码 -->
  <el-dialog v-model="pwdDialog.visible" :title="`重置密码 - ${pwdDialog.username}`" width="420px">
    <el-form label-width="80px" @submit.prevent>
      <el-form-item label="新密码">
        <el-input v-model="pwdDialog.password" type="password" show-password placeholder="至少 6 位" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="pwdDialog.visible = false">取消</el-button>
      <el-button type="primary" @click="submitResetPwd">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 14px;
}

.spacer {
  flex: 1;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.tip {
  margin-top: 8px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>

