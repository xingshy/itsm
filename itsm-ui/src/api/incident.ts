import http from "./http";
export interface Page<T> {
  total: number;
  pageNum: number;
  pageSize: number;
  records: T[];
}
export interface Incident {
  id: string;
  ticketNo: string | null;
  title: string;
  description: string;
  category: string;
  impact: string;
  urgency: string;
  priority: string;
  status: string;
  creatorId: string;
  creatorName: string;
  reporterId: string;
  reporterName: string;
  departmentId: string | null;
  departmentName: string | null;
  ownerId: string | null;
  ownerName: string | null;
  pendingUserId: string | null;
  pendingUserName: string | null;
  createdAt: string;
  updatedAt: string;
  submittedAt: string | null;
  version: number;
  cycle: number;
  responseDueAt: string | null;
  resolutionDueAt: string | null;
  firstResponseAt: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  archivedAt: string | null;
  occurredAt: string | null;
  expectedResumeAt: string | null;
  suspendPause: boolean;
  processingSeconds: number;
  allowedActions: string[];
  pendingTask: Record<string, any> | null;
}
export interface Recipient {
  id: string;
  username: string;
  nickname: string;
  deptId: string;
  deptName: string;
}
export interface Options {
  canAssign?: boolean;
  categories: { code: string; label: string }[];
  departments: { id: string; name: string }[];
  roles: { code: string; name: string }[];
}
export const statuses: Record<string, string> = {
  DRAFT: "草稿",
  PENDING_ASSIGNMENT: "待分配",
  PENDING_TAKEOVER: "待接管",
  PROCESSING: "处理中",
  SUSPENDED: "已挂起",
  RESOLVED: "已解决",
  CLOSED: "已关闭",
  ARCHIVED: "已归档",
};
export const actions: Record<string, string> = {
  remind: "催办",
  edit: "编辑",
  submit: "提交草稿",
  "delete-draft": "删除草稿",
  assign: "分配",
  transfer: "转交",
  forward: "转发",
  accept: "接管",
  withdraw: "撤回接管请求",
  "return-request": "申请退回",
  "return-decision": "审批退回",
  supplement: "补充说明",
  "processing-comment": "处理意见",
  suspend: "挂起",
  resume: "解除挂起",
  resolve: "解决",
  "reject-resolution": "退回处理",
  close: "确认关闭",
  archive: "归档",
  reopen: "重开",
  "reopen-request": "申请重开",
  reply: "协作回复",
  "forward-withdraw": "撤回转发",
};
export function date(value: unknown): string {
  return value ? new Date(String(value)).toLocaleString() : "—";
}
export function overdue(t: Incident): boolean {
  return (
    ((["PENDING_ASSIGNMENT", "PENDING_TAKEOVER", "PROCESSING"].includes(
      t.status,
    ) ||
      (t.status === "SUSPENDED" && !t.suspendPause)) &&
      !!t.resolutionDueAt &&
      Date.parse(t.resolutionDueAt) < Date.now()) ||
    (!t.firstResponseAt &&
      !!t.responseDueAt &&
      Date.parse(t.responseDueAt) < Date.now())
  );
}
export const incidentApi = {
  list: (params: Record<string, unknown>) =>
    http.get<never, Page<Incident>>("/incidents", { params }),
  detail: (id: string) => http.get<never, Incident>(`/incidents/${id}`),
  options: () => http.get<never, Options>("/incidents/options"),
  recipients: (params: Record<string, unknown>) =>
    http.get<never, Page<Recipient>>("/incidents/recipient-options", {
      params,
    }),
  records: (id: string, kind: string, params: Record<string, unknown> = {}) =>
    http.get<never, Page<Record<string, any>>>(`/incidents/${id}/${kind}`, {
      params,
    }),
  create: (body: Record<string, unknown>, key: string) =>
    http.post<never, Incident>("/incidents", body, {
      headers: { "Idempotency-Key": key },
    }),
  mutate: (
    id: string,
    path: string,
    body: Record<string, unknown>,
    key: string,
    method = "post",
  ) =>
    http.request<never, Incident>({
      url: `/incidents/${id}/${path}`,
      method,
      data: body,
      headers: { "Idempotency-Key": key },
    }),
  edit: (id: string, body: Record<string, unknown>, key: string) =>
    http.put<never, Incident>(`/incidents/${id}`, body, {
      headers: { "Idempotency-Key": key },
    }),
  counters: () =>
    http.get<never, Record<string, number>>("/incidents/counters"),
};

export const activityLabels: Record<string, string> = {
  REMIND: "催办",
  DRAFT_CREATED: "保存草稿",
  CREATED: "创建事件",
  EDIT: "修改信息",
  SUBMIT: "提交草稿",
  DELETE_DRAFT: "删除草稿",
  ASSIGN: "分配",
  TRANSFER: "转交",
  ACCEPT: "接管",
  WITHDRAW: "撤回接管请求",
  RETURN_REQUEST: "申请退回",
  RETURN_DECISION: "审批退回",
  FORWARD: "转发",
  READ: "阅读协作",
  REPLY: "协作回复",
  FORWARD_WITHDRAW: "撤回转发",
  COMMENT: "提交意见",
  SUSPEND: "挂起",
  RESUME: "解除挂起",
  RESOLVE: "解决",
  REJECT_RESOLUTION: "退回处理",
  CLOSE: "关闭",
  ARCHIVE: "归档",
  REOPEN: "重开",
  REOPEN_REQUEST: "申请重开",
  ATTACHMENT_ADDED: "添加附件",
  TAKEOVER_OVERDUE: "接管超时提醒",
  RESPONSE_OVERDUE: "响应超时提醒",
  RESOLUTION_OVERDUE: "处理超时提醒",
  RESUME_OVERDUE: "挂起超时提醒",
};
export const detailLabels: Record<string, string> = {
  responseDueAt: "响应期限",
  resolutionDueAt: "解决期限",
  reason: "原因",
  content: "意见内容",
  type: "意见类型",
  progress: "当前进展",
  remaining: "未完成事项",
  taskId: "接管任务",
  threadId: "协作线程",
  sourceUserId: "原处理人编号",
  targetUserId: "目标人员编号",
  departmentId: "部门编号",
  sourceUserName: "原处理人",
  targetUserName: "目标人员",
  sourceDepartmentName: "原部门",
  targetDepartmentName: "目标部门",
  approved: "审批结果",
  analysis: "原因分析",
  measures: "采取措施",
  result: "处理结果",
  verification: "验证说明",
  recipientIds: "接收人编号",
  attachmentIds: "附件记录",
  commentId: "意见记录",
  correctsCommentId: "更正原意见",
  expectedResumeAt: "预计恢复时间",
  pausePolicy: "暂停解决时钟",
  pausedSeconds: "暂停秒数",
  exceptionCorrection: "服务台纠正",
  priority: "优先级",
  title: "标题",
  description: "描述",
  category: "分类",
  impact: "影响度",
  urgency: "紧急度",
  occurredAt: "发生时间",
  invalid: "无效关闭",
  reference: "提醒依据",
};
