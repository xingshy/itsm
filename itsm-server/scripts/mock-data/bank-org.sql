-- =====================================================================
-- 银行组织架构 + 员工模拟数据(仅开发/演示,不放入 Flyway)
-- =====================================================================
-- 组织结构:总行(根,id=1,由"总部"改名)
--   ├── 14 个总行部门:办公室/人力资源部/计划财务部/风险管理部/授信审批部/
--   │     合规部/内审部/信息科技部/运营管理部/个人金融部/公司金融部/
--   │     金融市场部/普惠金融部/信用卡中心
--   └── 4 家分行(北京/上海/深圳/杭州),每家下辖 1 营业部 + 3 支行
-- 人员:136 名(bank_emp_0001 ~ bank_emp_0136),每部门 4 人
--   · 每部门第 1 人为负责人:回填 dept.leader/phone,并授予 MANAGER 角色
--   · 全部员工授予 EMPLOYEE 角色;统一密码 mock123456
--   · 工号 employee_no 先取 bank_emp_XXXX;导入后可执行同目录
--     rename-users-pinyin.sql 将登录名改为「姓全拼+名首字母」,工号保持不变
-- 幂等:重复执行自动跳过已存在的部门/账号,不覆盖已有资料
-- =====================================================================
BEGIN;
SET LOCAL search_path TO itsm;
LOCK TABLE sys_dept, sys_user, sys_role, sys_user_role IN SHARE ROW EXCLUSIVE MODE;

-- ---------- 前置校验 ----------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 1 AND NOT deleted AND status = 1) THEN
        RAISE EXCEPTION '需要启用的根部门 (id=1),请先运行应用数据库迁移';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'EMPLOYEE' AND NOT deleted AND status = 1) THEN
        RAISE EXCEPTION '需要启用的 EMPLOYEE 角色,请先运行应用数据库迁移';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'MANAGER' AND NOT deleted AND status = 1) THEN
        RAISE EXCEPTION '需要启用的 MANAGER 角色,请先运行应用数据库迁移';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_user WHERE username ~ '^bank_emp_[0-9]{4}$' AND NOT deleted AND created_by IS DISTINCT FROM 'mock-data') THEN
        RAISE EXCEPTION '模拟账号前缀与已有用户冲突,已取消导入';
    END IF;
END $$;

-- ---------- 根部门更名:总部 → 总行 ----------
UPDATE sys_dept SET name = '总行', updated_by = 'mock-data', updated_at = now()
WHERE id = 1 AND name = '总部' AND NOT deleted;

-- ---------- 部门定义 ----------
-- assign_seq:人员分配顺序(1~34,每部门固定 4 人);level 1=挂总行,level 2=挂分行
CREATE TEMP TABLE mock_bank_depts (
    assign_seq INT PRIMARY KEY,
    key        TEXT NOT NULL,
    parent_key TEXT,
    name       TEXT NOT NULL,
    sort       INT  NOT NULL,
    level      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO mock_bank_depts (assign_seq, key, parent_key, name, sort, level) VALUES
-- 总行部门
(1,  'ho_office',     NULL, '办公室',       1,  1),
(2,  'ho_hr',         NULL, '人力资源部',   2,  1),
(3,  'ho_finance',    NULL, '计划财务部',   3,  1),
(4,  'ho_risk',       NULL, '风险管理部',   4,  1),
(5,  'ho_credit',     NULL, '授信审批部',   5,  1),
(6,  'ho_compliance', NULL, '合规部',       6,  1),
(7,  'ho_audit',      NULL, '内审部',       7,  1),
(8,  'ho_it',         NULL, '信息科技部',   8,  1),
(9,  'ho_ops',        NULL, '运营管理部',   9,  1),
(10, 'ho_retail',     NULL, '个人金融部',   10, 1),
(11, 'ho_corp',       NULL, '公司金融部',   11, 1),
(12, 'ho_market',     NULL, '金融市场部',   12, 1),
(13, 'ho_inclusive',  NULL, '普惠金融部',   13, 1),
(14, 'ho_card',       NULL, '信用卡中心',   14, 1),
-- 一级分行
(15, 'bj_branch',     NULL, '北京分行',     20, 1),
(16, 'sh_branch',     NULL, '上海分行',     21, 1),
(17, 'sz_branch',     NULL, '深圳分行',     22, 1),
(18, 'hz_branch',     NULL, '杭州分行',     23, 1),
-- 北京分行下辖
(19, 'bj_op', 'bj_branch', '北京分行营业部', 1, 2),
(20, 'bj_cy', 'bj_branch', '朝阳支行',       2, 2),
(21, 'bj_hd', 'bj_branch', '海淀支行',       3, 2),
(22, 'bj_xc', 'bj_branch', '西城支行',       4, 2),
-- 上海分行下辖
(23, 'sh_op', 'sh_branch', '上海分行营业部', 1, 2),
(24, 'sh_pd', 'sh_branch', '浦东支行',       2, 2),
(25, 'sh_ja', 'sh_branch', '静安支行',       3, 2),
(26, 'sh_xh', 'sh_branch', '徐汇支行',       4, 2),
-- 深圳分行下辖
(27, 'sz_op', 'sz_branch', '深圳分行营业部', 1, 2),
(28, 'sz_ft', 'sz_branch', '福田支行',       2, 2),
(29, 'sz_ns', 'sz_branch', '南山支行',       3, 2),
(30, 'sz_lh', 'sz_branch', '罗湖支行',       4, 2),
-- 杭州分行下辖
(31, 'hz_op', 'hz_branch', '杭州分行营业部', 1, 2),
(32, 'hz_xh', 'hz_branch', '西湖支行',       2, 2),
(33, 'hz_bj', 'hz_branch', '滨江支行',       3, 2),
(34, 'hz_yh', 'hz_branch', '余杭支行',       4, 2);

-- 1) 插入总行部门与分行(父 = 总行 id=1,ancestors = '0,1')
INSERT INTO sys_dept (id, parent_id, ancestors, name, sort, status, created_by)
SELECT (SELECT COALESCE(max(id), 0) FROM sys_dept) + row_number() OVER (ORDER BY assign_seq),
       1, '0,1', name, sort, 1, 'mock-data'
FROM mock_bank_depts
WHERE level = 1
  AND NOT EXISTS (SELECT 1 FROM sys_dept d
                  WHERE d.parent_id = 1 AND d.name = mock_bank_depts.name AND NOT d.deleted);

-- 2) 插入支行/营业部(父 = 对应分行,ancestors = '0,1,<分行id>')
INSERT INTO sys_dept (id, parent_id, ancestors, name, sort, status, created_by)
SELECT (SELECT COALESCE(max(id), 0) FROM sys_dept) + row_number() OVER (ORDER BY a.assign_seq),
       p.id, '0,1,' || p.id, a.name, a.sort, 1, 'mock-data'
FROM mock_bank_depts a
JOIN mock_bank_depts pd ON pd.key = a.parent_key
JOIN sys_dept p ON p.parent_id = 1 AND p.name = pd.name AND NOT p.deleted
WHERE a.level = 2
  AND NOT EXISTS (SELECT 1 FROM sys_dept d
                  WHERE d.parent_id = p.id AND d.name = a.name AND NOT d.deleted);

-- ---------- 人员生成 ----------
-- 姓名:按 md5(g) 确定性取模,从 60 姓 × 48 名中选取;固定种子,可重复执行得到同样结果
CREATE TEMP TABLE mock_bank_users (
    g         INT PRIMARY KEY,          -- 员工序号 1~136
    username  TEXT NOT NULL,
    nickname  TEXT NOT NULL,
    dept_id   BIGINT NOT NULL,
    is_leader BOOLEAN NOT NULL
) ON COMMIT DROP;

INSERT INTO mock_bank_users (g, username, nickname, dept_id, is_leader)
WITH
s AS (SELECT array_agg(s ORDER BY ord) AS a FROM (VALUES
    ('王',1),('李',2),('张',3),('刘',4),('陈',5),('杨',6),('赵',7),('黄',8),('周',9),('吴',10),
    ('徐',11),('孙',12),('胡',13),('朱',14),('高',15),('林',16),('何',17),('郭',18),('马',19),('罗',20),
    ('梁',21),('宋',22),('郑',23),('谢',24),('韩',25),('唐',26),('冯',27),('于',28),('董',29),('程',30),
    ('曹',31),('袁',32),('邓',33),('许',34),('傅',35),('沈',36),('曾',37),('彭',38),('吕',39),('苏',40),
    ('卢',41),('蒋',42),('蔡',43),('贾',44),('丁',45),('魏',46),('薛',47),('叶',48),('余',49),('潘',50),
    ('杜',51),('戴',52),('夏',53),('钟',54),('汪',55),('田',56),('任',57),('姜',58),('范',59),('方',60)
) t(s, ord)),
n AS (SELECT array_agg(n ORDER BY ord) AS a FROM (VALUES
    ('浩然',1),('子涵',2),('雨桐',3),('梓萱',4),('欣怡',5),('俊杰',6),('明轩',7),('思远',8),('佳琪',9),('若溪',10),
    ('博文',11),('静怡',12),('志诚',13),('梦琪',14),('雅婷',15),('安然',16),('书瑶',17),('景行',18),('承泽',19),('清扬',20),
    ('诗涵',21),('嘉宁',22),('瑞霖',23),('晨曦',24),('宇航',25),('可欣',26),('一诺',27),('天佑',28),('晓彤',29),('浩宇',30),
    ('俊熙',31),('明杰',32),('佳怡',33),('文轩',34),('静姝',35),('志远',36),('梦瑶',37),('雅静',38),('安琪',39),('书涵',40),
    ('承志',41),('清源',42),('诗雨',43),('嘉懿',44),('瑞雪',45),('晨光',46),('宇轩',47),('可馨',48)
) t(n, ord)),
gen AS (
    SELECT g,
           'bank_emp_' || lpad(g::text, 4, '0') AS username,
           (SELECT a FROM s)[1 + (('x' || substr(md5(g::text || '-sn'), 1, 8))::bit(32)::bigint % 60)]
         || (SELECT a FROM n)[1 + (('x' || substr(md5(g::text || '-gn'), 1, 8))::bit(32)::bigint % 48)] AS nickname,
           (g - 1) / 4 + 1 AS dept_seq,
           (g - 1) % 4 = 0 AS is_leader
    FROM generate_series(1, 136) g
)
SELECT gen.g, gen.username, gen.nickname, d.id, gen.is_leader
FROM gen
JOIN mock_bank_depts b ON b.assign_seq = gen.dept_seq
JOIN sys_dept d ON d.name = b.name AND NOT d.deleted AND d.status = 1;

-- 插入用户(密码统一 123456,BCrypt 散列;工号先取账号形式)
INSERT INTO sys_user (id, username, password, nickname, email, phone, employee_no, dept_id, status, created_by)
SELECT (SELECT COALESCE(max(id), 0) FROM sys_user) + row_number() OVER (ORDER BY m.g),
       m.username,
       '$2a$10$PE6XlqsvfAuBfweDgeLPMuNDMG1isXQsWlTDkWBWzAB.gbfPhQ0CW',
       m.nickname,
       m.username || '@example.com',
       '139' || lpad(m.g::text, 8, '0'),
       m.username,
       m.dept_id, 1, 'mock-data'
FROM mock_bank_users m
WHERE NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.username = m.username AND NOT u.deleted);

-- 回填部门负责人与联系电话(每部门第 1 人)
UPDATE sys_dept d
SET leader = m.nickname,
    phone  = '139' || lpad(m.g::text, 8, '0'),
    updated_by = 'mock-data', updated_at = now()
FROM mock_bank_users m
WHERE m.dept_id = d.id AND m.is_leader AND NOT d.deleted;

-- ---------- 角色分配 ----------
-- 全部员工 → EMPLOYEE
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM mock_bank_users m
JOIN sys_user u ON u.username = m.username AND NOT u.deleted AND u.created_by = 'mock-data'
JOIN sys_role r ON r.code = 'EMPLOYEE' AND NOT r.deleted
ON CONFLICT DO NOTHING;

-- 各部门负责人 → MANAGER
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM mock_bank_users m
JOIN sys_user u ON u.username = m.username AND NOT u.deleted AND u.created_by = 'mock-data'
JOIN sys_role r ON r.code = 'MANAGER' AND NOT r.deleted
WHERE m.is_leader
ON CONFLICT DO NOTHING;

-- ---------- 显式分配 ID 后前移序列,避免后续默认 ID 与种子数据冲突 ----------
SELECT setval(pg_get_serial_sequence('sys_dept', 'id'),
              GREATEST((SELECT max(id) FROM sys_dept), (SELECT last_value FROM sys_dept_id_seq)));
SELECT setval(pg_get_serial_sequence('sys_user', 'id'),
              GREATEST((SELECT max(id) FROM sys_user), (SELECT last_value FROM sys_user_id_seq)));

-- ---------- 导入结果统计 ----------
SELECT d.name AS department, d.leader, count(u.id) AS employees
FROM mock_bank_depts b
JOIN sys_dept d ON d.name = b.name AND NOT d.deleted
LEFT JOIN sys_user u ON u.dept_id = d.id AND NOT u.deleted AND u.created_by = 'mock-data'
GROUP BY b.assign_seq, d.id, d.name, d.leader
ORDER BY b.assign_seq;

SELECT count(*) AS total_users FROM sys_user WHERE created_by = 'mock-data' AND NOT deleted;
COMMIT;

