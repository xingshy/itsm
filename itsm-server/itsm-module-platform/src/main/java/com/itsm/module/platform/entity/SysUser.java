package com.itsm.module.platform.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.itsm.domain.base.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/** 用户 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseDO {

    private String username;
    private String password;
    private String nickname;
    private String email;
    private String phone;
    /** 工号(可与登录名不同,如 mock 数据 bank_emp_XXXX) */
    private String employeeNo;
    private Long deptId;
    private Integer status;
    private OffsetDateTime lastLoginAt;
    @TableLogic
    private Boolean deleted;
}

