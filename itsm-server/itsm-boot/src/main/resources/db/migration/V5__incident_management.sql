-- Unified ticket backbone. Historical prototype tickets were never persisted.
CREATE TABLE ticket (
 id BIGSERIAL PRIMARY KEY, ticket_no VARCHAR(40) UNIQUE, type VARCHAR(32) NOT NULL DEFAULT 'INCIDENT',
 title VARCHAR(200) NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT '', category VARCHAR(64),
 impact VARCHAR(16), urgency VARCHAR(16), priority VARCHAR(4), source VARCHAR(16) NOT NULL DEFAULT 'PORTAL',
 status VARCHAR(32) NOT NULL DEFAULT 'DRAFT', creator_id BIGINT NOT NULL, creator_name VARCHAR(64) NOT NULL,
 reporter_id BIGINT NOT NULL, reporter_name VARCHAR(64) NOT NULL, report_dept_id BIGINT, report_dept_name VARCHAR(64),
 occurred_at TIMESTAMPTZ, submitted_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 department_id BIGINT, department_name VARCHAR(64), owner_id BIGINT, owner_name VARCHAR(64), pending_user_id BIGINT, pending_user_name VARCHAR(64),
 draft_deleted BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0, cycle INT NOT NULL DEFAULT 1, action_seq BIGINT NOT NULL DEFAULT 0,
 first_response_at TIMESTAMPTZ, response_due_at TIMESTAMPTZ, resolution_due_at TIMESTAMPTZ,
 resolved_at TIMESTAMPTZ, closed_at TIMESTAMPTZ, archived_at TIMESTAMPTZ,
 resolved_by BIGINT, closed_by BIGINT, archived_by BIGINT,
 suspended_at TIMESTAMPTZ, expected_resume_at TIMESTAMPTZ, suspend_pause BOOLEAN NOT NULL DEFAULT TRUE, paused_seconds BIGINT NOT NULL DEFAULT 0,
 CHECK (type IN ('INCIDENT','REQUEST')), CHECK (status IN ('DRAFT','PENDING_ASSIGNMENT','PENDING_TAKEOVER','PROCESSING','SUSPENDED','RESOLVED','CLOSED','ARCHIVED'))
);
CREATE INDEX idx_ticket_status ON ticket(status, updated_at DESC);
CREATE INDEX idx_ticket_creator ON ticket(creator_id, created_at DESC);
CREATE INDEX idx_ticket_owner ON ticket(owner_id);
CREATE INDEX idx_ticket_pending ON ticket(pending_user_id);
CREATE INDEX idx_ticket_dept ON ticket(department_id);
CREATE TABLE incident_assignment (
 id BIGSERIAL PRIMARY KEY, ticket_id BIGINT NOT NULL REFERENCES ticket(id), kind VARCHAR(16) NOT NULL,
 source_user_id BIGINT, source_user_name VARCHAR(64), target_user_id BIGINT NOT NULL, target_user_name VARCHAR(64) NOT NULL,
 department_id BIGINT NOT NULL, department_name VARCHAR(64) NOT NULL, initiator_id BIGINT NOT NULL,
 reason TEXT NOT NULL, progress TEXT, remaining TEXT, status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 return_reason TEXT, return_requested BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 due_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ, cycle INT NOT NULL
);
CREATE UNIQUE INDEX uk_incident_pending ON incident_assignment(ticket_id) WHERE status='PENDING';
CREATE TABLE incident_responsibility (
 id BIGSERIAL PRIMARY KEY, ticket_id BIGINT NOT NULL REFERENCES ticket(id), user_id BIGINT NOT NULL, user_name VARCHAR(64) NOT NULL,
 department_id BIGINT, department_name VARCHAR(64), cycle INT NOT NULL, started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 ended_at TIMESTAMPTZ, start_reason VARCHAR(32) NOT NULL, end_reason VARCHAR(32)
);
CREATE UNIQUE INDEX uk_incident_responsibility ON incident_responsibility(ticket_id) WHERE ended_at IS NULL;
CREATE INDEX idx_incident_responsibility_user ON incident_responsibility(user_id, ticket_id);
CREATE TABLE incident_forward (
 id BIGSERIAL PRIMARY KEY, ticket_id BIGINT NOT NULL REFERENCES ticket(id), initiator_id BIGINT NOT NULL,
 initiator_name VARCHAR(64) NOT NULL, reason TEXT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), ended_at TIMESTAMPTZ
);
CREATE TABLE incident_forward_recipient (
 thread_id BIGINT NOT NULL REFERENCES incident_forward(id), user_id BIGINT NOT NULL, user_name VARCHAR(64) NOT NULL,
 read_at TIMESTAMPTZ, PRIMARY KEY(thread_id,user_id)
);
CREATE INDEX idx_incident_forward_user ON incident_forward_recipient(user_id);
CREATE TABLE incident_comment (
 id BIGSERIAL PRIMARY KEY, ticket_id BIGINT NOT NULL REFERENCES ticket(id), type VARCHAR(32) NOT NULL,
 content TEXT NOT NULL, author_id BIGINT NOT NULL, author_name VARCHAR(64) NOT NULL,
 cycle INT NOT NULL, responsibility_id BIGINT, corrects_comment_id BIGINT REFERENCES incident_comment(id), thread_id BIGINT REFERENCES incident_forward(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_incident_comment_ticket ON incident_comment(ticket_id,id);
CREATE TABLE ticket_action (
 id BIGSERIAL PRIMARY KEY, ticket_id BIGINT NOT NULL REFERENCES ticket(id), seq BIGINT NOT NULL,
 action VARCHAR(64) NOT NULL, actor_id BIGINT NOT NULL, actor_name VARCHAR(64) NOT NULL, actor_dept_name VARCHAR(64),
 before_status VARCHAR(32) NOT NULL, after_status VARCHAR(32) NOT NULL, details TEXT NOT NULL,
 cycle INT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(ticket_id,seq)
);
CREATE TABLE incident_request (
 user_id BIGINT NOT NULL, request_key VARCHAR(128) NOT NULL, fingerprint TEXT NOT NULL, response TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(user_id,request_key)
);
CREATE TABLE incident_notification (
 id BIGSERIAL PRIMARY KEY, ticket_id BIGINT NOT NULL REFERENCES ticket(id), user_id BIGINT NOT NULL,
 action_id BIGINT NOT NULL REFERENCES ticket_action(id), kind VARCHAR(64) NOT NULL, message TEXT NOT NULL,
 read_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(user_id,action_id,kind)
);
CREATE INDEX idx_incident_notification_user ON incident_notification(user_id,read_at);
CREATE TABLE incident_attachment (
 id BIGSERIAL PRIMARY KEY, ticket_id BIGINT NOT NULL REFERENCES ticket(id), thread_id BIGINT REFERENCES incident_forward(id),
 uploader_id BIGINT NOT NULL, comment_id BIGINT REFERENCES incident_comment(id), action_id BIGINT REFERENCES ticket_action(id), filename VARCHAR(255) NOT NULL, content_type VARCHAR(128) NOT NULL,
 storage_key VARCHAR(255) NOT NULL, size BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO sys_dict(dict_type,code,label,sort) VALUES
 ('incident_category','NETWORK','网络',1),('incident_category','SYSTEM','系统',2),('incident_category','DEVICE','设备',3),
 ('incident_category','ACCOUNT','账号',4),('incident_category','OTHER','其他',5);
-- SLA values are explicit configurable defaults, not implied organizational commitments.
INSERT INTO sys_config(config_key,config_value,remark) VALUES
 ('incident.attachment.max-mb','20','单个附件最大MB'),('incident.attachment.max-count','10','单次上传数量上限'),
 ('incident.attachment.quota-mb','200','每个事件附件总配额MB'),
 ('incident.attachment.extensions','pdf,txt,csv,png,jpg,jpeg,gif,docx,xlsx,pptx,log,zip','附件扩展名白名单'),
 ('incident.takeover.minutes','120','每次接管期限（自然分钟）'),
 ('incident.sla.P1','15,240','响应分钟,解决分钟'),('incident.sla.P2','30,480','响应分钟,解决分钟'),
 ('incident.sla.P3','120,1440','响应分钟,解决分钟'),('incident.sla.P4','240,2880','响应分钟,解决分钟'),
 ('incident.suspend.pause','true','等待外部条件挂起时暂停解决时钟');
SELECT setval(pg_get_serial_sequence('sys_menu','id'),GREATEST((SELECT MAX(id) FROM sys_menu), (SELECT last_value FROM sys_menu_id_seq)));
INSERT INTO sys_menu(parent_id,name,type,path,component,perm,sort)
 VALUES(2,'事件管理','C','/incidents','incident/index','itsm:incident:list',2);
INSERT INTO sys_menu(parent_id,name,type,perm)
 SELECT m.id, p.label, 'F', 'itsm:incident:'||p.code FROM sys_menu m CROSS JOIN
 (VALUES ('detail','查看'),('create','创建'),('edit','编辑'),('assign','分配'),('forward','转发'),('transfer','转交'),
 ('accept','接管'),('comment','意见'),('suspend','挂起'),('resolve','解决'),('close','关闭'),('archive','归档'),('reopen','重开'),('audit','审计'),('remind','催办')) p(code,label)
 WHERE m.perm='itsm:incident:list';
INSERT INTO sys_role_menu(role_id,menu_id)
 SELECT r.id,m.id FROM sys_role r CROSS JOIN sys_menu m WHERE m.perm LIKE 'itsm:incident:%' AND
 (r.code IN ('ADMIN','SEAT') OR (r.code='ENGINEER' AND m.perm NOT IN ('itsm:incident:assign','itsm:incident:archive','itsm:incident:audit','itsm:incident:remind'))
 OR (r.code='EMPLOYEE' AND m.perm IN ('itsm:incident:list','itsm:incident:detail','itsm:incident:create','itsm:incident:edit','itsm:incident:comment','itsm:incident:close'))
 OR (r.code='MANAGER' AND m.perm IN ('itsm:incident:list','itsm:incident:detail','itsm:incident:audit')));
SELECT setval(pg_get_serial_sequence('sys_menu','id'),(SELECT MAX(id) FROM sys_menu));

CREATE TABLE incident_cycle (
 ticket_id BIGINT NOT NULL REFERENCES ticket(id), cycle INT NOT NULL, started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 ended_at TIMESTAMPTZ, paused_seconds BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(ticket_id,cycle)
);
CREATE TABLE incident_reminder (
 ticket_id BIGINT NOT NULL REFERENCES ticket(id), kind VARCHAR(32) NOT NULL, reference VARCHAR(80) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(ticket_id,kind,reference)
);
CREATE TABLE incident_notification_outbox (
 id BIGSERIAL PRIMARY KEY, ticket_id BIGINT NOT NULL REFERENCES ticket(id), user_id BIGINT NOT NULL,
 action_id BIGINT NOT NULL REFERENCES ticket_action(id), kind VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(512),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), delivered_at TIMESTAMPTZ, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(user_id,action_id,kind)
);
CREATE INDEX idx_incident_outbox_pending ON incident_notification_outbox(status,next_attempt_at);
