package com.itsm.module.platform.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.OffsetDateTime;
import java.util.List;

/** 用户视图 */
public record UserVO(
        Long id,
        String username,
        String employeeNo,
        String nickname,
        @JsonSerialize(using = ToStringSerializer.class) Long deptId,
        String deptName,
        String email,
        String phone,
        Integer status,
        List<Long> roleIds,
        List<String> roleNames,
        OffsetDateTime createdAt) {
}

