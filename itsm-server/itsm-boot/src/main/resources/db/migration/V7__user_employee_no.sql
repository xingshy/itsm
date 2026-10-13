-- =====================================================================
-- V7 用户工号:登录名(拼音)与工号解耦,工号独立于 username 维护
-- =====================================================================
ALTER TABLE sys_user ADD COLUMN employee_no VARCHAR(64);
COMMENT ON COLUMN sys_user.employee_no IS '工号(员工编号,可与登录名不同)';

-- 启用中的工号唯一(允许为空:工号为可选字段)
CREATE UNIQUE INDEX uk_sys_user_employee_no
    ON sys_user (employee_no) WHERE deleted = FALSE AND employee_no IS NOT NULL;

