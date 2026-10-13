/**
 * 系统管理 - 人员管理(真实后端接口,对接 itsm-module-platform)。
 *
 * 用户:  GET/POST /users  PUT /users/{id}  DELETE /users/{ids}  PUT /users/{id}/password
 * 部门:  GET /depts/tree(用户表单选部门用)
 * 角色:  GET /roles(用户表单选角色用)
 *
 * 删除说明:后端 DELETE /users/{ids} 为逻辑删除 —— 仅将 sys_user.deleted 标记为 true,
 * 不物理删除人员记录,也不清理角色关联,支持数据恢复。
 */
import http from './http';

// ================= 后端统一分页结构(PageResult<T>) =================

export interface PageResult<T> {
  total: number;
  pageNum: number;
  pageSize: number;
  records: T[];
}

// ================= 部门树(后端 DeptNode) =================

export { fetchDeptTree, type DeptNode } from './dept';

// ================= 角色(后端 RoleVO) =================

export interface RoleVO {
  id: number;
  name: string;
  code: string;
  dataScope: string;
  status: 0 | 1;
  remark: string | null;
  menuIds: number[];
}

// ================= 用户(后端 UserVO / Cmd) =================

export interface UserVO {
  id: number;
  username: string;
  /** 工号(可与登录名不同) */
  employeeNo: string | null;
  nickname: string;
  deptId: string | null;
  deptName: string | null;
  email: string | null;
  phone: string | null;
  status: 0 | 1;
  roleIds: number[];
  roleNames: string[];
  /** yyyy-MM-dd HH:mm:ss(后端 jackson date-format) */
  createdAt: string;
}

export interface UserPageQuery {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  deptId?: string;
  status?: 0 | 1;
}

export interface UserCreateCmd {
  username: string;
  password: string;
  nickname: string;
  deptId: string | null;
  email: string | null;
  phone: string | null;
  status: 0 | 1;
  roleIds: number[];
}

export interface UserUpdateCmd {
  nickname: string;
  deptId: string | null;
  email: string | null;
  phone: string | null;
  status: 0 | 1;
  roleIds: number[];
}

// ================= API =================

/** 分页查询用户 */
export function pageUsers(query: UserPageQuery): Promise<PageResult<UserVO>> {
  return http.get<never, PageResult<UserVO>>('/users', { params: query });
}

/** 新增人员 */
export function createUser(cmd: UserCreateCmd): Promise<number> {
  return http.post<never, number>('/users', cmd);
}

/** 修改人员(不含用户名与密码) */
export function updateUser(id: number, cmd: UserUpdateCmd): Promise<void> {
  return http.put<never, void>(`/users/${id}`, cmd);
}

/** 删除人员(逻辑删除:仅标记 deleted=true,保留记录) */
export function deleteUsers(ids: number[]): Promise<void> {
  return http.delete<never, void>(`/users/${ids.join(',')}`);
}

/** 重置密码 */
export function resetUserPassword(id: number, newPassword: string): Promise<void> {
  return http.put<never, void>(`/users/${id}/password`, { newPassword });
}

/** 角色列表(下拉选项) */
export function fetchRoles(): Promise<RoleVO[]> {
  return http.get<never, RoleVO[]>('/roles');
}

