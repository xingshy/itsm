package com.itsm.module.platform.dto;

import java.util.List;
import java.util.Set;

/** 当前用户信息(/me) */
public record MeVO(
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long userId,
        String username,
        String nickname,
        Set<String> roles,
        Set<String> perms,
        List<MenuNode> menus) {
}

