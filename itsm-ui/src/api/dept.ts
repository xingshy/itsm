import http from './http';
import type {PageResult} from './user';

export interface DeptNode {
  id: string;
  parentId: string;
  name: string;
  sort: number;
  leader: string | null;
  phone: string | null;
  email: string | null;
  status: 0 | 1;
  children: DeptNode[];
}
export type DeptSaveCmd = Omit<DeptNode, 'id' | 'children'>;
export function fetchDeptTree(): Promise<DeptNode[]> {
  return http.get<never, DeptNode[]>('/depts/tree');
}
export function createDept(cmd: DeptSaveCmd): Promise<string> {
  return http.post<never, string>('/depts', cmd);
}
export function updateDept(id: string, cmd: DeptSaveCmd): Promise<void> {
  return http.put<never, void>(`/depts/${id}`, cmd);
}
export function deleteDept(id: string): Promise<void> {
  return http.delete<never, void>(`/depts/${id}`);
}

export interface LeaderOption {
  id: number;
  username: string;
  /** 工号(可与登录名不同) */
  employeeNo: string | null;
  nickname: string;
  email: string | null;
  phone: string | null;
}
export function fetchLeaderOptions(keyword = ''): Promise<LeaderOption[]> {
  return http.get<never, LeaderOption[]>('/depts/leader-options', { params: { keyword } });
}

export function fetchDeptEmployees(id: string, pageNum = 1, pageSize = 20): Promise<PageResult<LeaderOption>> {
  return http.get<never, PageResult<LeaderOption>>(`/depts/${id}/employees`, { params: { pageNum, pageSize } });
}
