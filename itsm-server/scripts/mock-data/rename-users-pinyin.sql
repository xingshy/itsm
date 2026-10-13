-- =====================================================================
-- 登录名改为「姓全拼 + 名首字母」,原 bank_emp_XXXX 账号转为工号
-- =====================================================================
-- 范围:仅 created_by='mock-data' 且登录名为 bank_emp_XXXX 形式的用户
-- 规则:杨承泽 → yang + cz = yangcz;重名者追加序号(wangcz2)
-- 同步:employee_no = 原账号;email = 新登录名@example.com
-- 幂等:已改名的用户(非 bank_emp_ 形式)自动跳过,可重复执行
-- 依赖:sys_user.employee_no 列(V7 迁移)
-- =====================================================================
BEGIN;
SET LOCAL search_path TO itsm;
LOCK TABLE sys_user IN SHARE ROW EXCLUSIVE MODE;

-- 自愈:此前若已按旧映射改名(名次字首字母丢失),先从工号恢复登录名再重新生成
UPDATE sys_user
SET username = employee_no,
    email    = employee_no || '@example.com',
    updated_by = 'mock-data', updated_at = now()
WHERE created_by = 'mock-data'
  AND employee_no LIKE 'bank\_emp\_%'
  AND NOT deleted
  AND username <> employee_no
  AND username NOT LIKE 'bank\_emp\_%';

-- 姓 → 全拼(与 bank-org.sql 姓氏库一致;多音字按姓氏读音)
CREATE TEMP TABLE py_surname (han text PRIMARY KEY, py text NOT NULL) ON COMMIT DROP;
INSERT INTO py_surname VALUES
('王','wang'),('李','li'),('张','zhang'),('刘','liu'),('陈','chen'),('杨','yang'),('赵','zhao'),('黄','huang'),('周','zhou'),('吴','wu'),
('徐','xu'),('孙','sun'),('胡','hu'),('朱','zhu'),('高','gao'),('林','lin'),('何','he'),('郭','guo'),('马','ma'),('罗','luo'),
('梁','liang'),('宋','song'),('郑','zheng'),('谢','xie'),('韩','han'),('唐','tang'),('冯','feng'),('于','yu'),('董','dong'),('程','cheng'),
('曹','cao'),('袁','yuan'),('邓','deng'),('许','xu'),('傅','fu'),('沈','shen'),('曾','zeng'),('彭','peng'),('吕','lv'),('苏','su'),
('卢','lu'),('蒋','jiang'),('蔡','cai'),('贾','jia'),('丁','ding'),('魏','wei'),('薛','xue'),('叶','ye'),('余','yu'),('潘','pan'),
('杜','du'),('戴','dai'),('夏','xia'),('钟','zhong'),('汪','wang'),('田','tian'),('任','ren'),('姜','jiang'),('范','fan'),('方','fang');

-- 名字用字 → 拼音首字母(覆盖 bank-org.sql 名字库全部 60 个字,含首字与次字)
CREATE TEMP TABLE py_initial (han text PRIMARY KEY, ini text NOT NULL) ON COMMIT DROP;
INSERT INTO py_initial VALUES
('浩','h'),('子','z'),('雨','y'),('梓','z'),('欣','x'),('俊','j'),('明','m'),('思','s'),('佳','j'),('若','r'),
('博','b'),('静','j'),('志','z'),('梦','m'),('雅','y'),('安','a'),('书','s'),('景','j'),('承','c'),('清','q'),
('诗','s'),('嘉','j'),('瑞','r'),('晨','c'),('宇','y'),('可','k'),('一','y'),('天','t'),('晓','x'),('文','w'),
('然','r'),('涵','h'),('桐','t'),('萱','x'),('怡','y'),('杰','j'),('轩','x'),('远','y'),('琪','q'),('溪','x'),
('诚','c'),('婷','t'),('瑶','y'),('行','x'),('泽','z'),('扬','y'),('宁','n'),('霖','l'),('曦','x'),('航','h'),
('诺','n'),('佑','y'),('彤','t'),('熙','x'),('姝','s'),('源','y'),('懿','y'),('雪','x'),('光','g'),('馨','x');

-- 待改名用户
CREATE TEMP TABLE rename_users AS
SELECT u.id, u.username AS old_username, u.nickname,
       substring(u.nickname, 1, 1) AS surname_char
FROM sys_user u
WHERE u.created_by = 'mock-data'
  AND u.username LIKE 'bank\_emp\_%'
  AND NOT u.deleted;

-- 生成新登录名:姓全拼 + 名首字母;同名按原账号排序追加序号
CREATE TEMP TABLE rename_final AS
WITH chars AS (
    SELECT r.id, g.pos, substring(r.nickname, g.pos, 1) AS ch
    FROM rename_users r
    CROSS JOIN LATERAL generate_series(2, greatest(char_length(r.nickname), 2)) g(pos)
),
initials AS (
    SELECT c.id, string_agg(pi.ini, '' ORDER BY c.pos) AS ini
    FROM chars c
    JOIN py_initial pi ON pi.han = c.ch
    GROUP BY c.id
),
base AS (
    SELECT r.id, r.old_username, r.nickname, s.py || coalesce(i.ini, '') AS base_name
    FROM rename_users r
    LEFT JOIN initials i ON i.id = r.id
    LEFT JOIN py_surname s ON s.han = r.surname_char
)
SELECT b.id, b.old_username, b.nickname, b.base_name,
       CASE WHEN b.rn = 1 THEN b.base_name ELSE b.base_name || b.rn::text END AS new_username
FROM (SELECT *, row_number() OVER (PARTITION BY base_name ORDER BY old_username) AS rn FROM base) b
WHERE b.base_name IS NOT NULL AND b.base_name <> '';

-- 改名 + 工号 + 邮箱
UPDATE sys_user u
SET username    = f.new_username,
    employee_no = f.old_username,
    email       = f.new_username || '@example.com',
    updated_by  = 'mock-data',
    updated_at  = now()
FROM rename_final f
WHERE u.id = f.id AND u.username = f.old_username;

-- 结果对照(前 20 行)与校验
SELECT f.old_username AS employee_no, f.nickname, f.new_username AS username
FROM rename_final f ORDER BY f.old_username LIMIT 20;

-- 映射缺失导致未改名的人数(应为 0)
SELECT count(*) AS not_renamed
FROM rename_users r
LEFT JOIN rename_final f ON f.id = r.id
WHERE f.id IS NULL;
COMMIT;

