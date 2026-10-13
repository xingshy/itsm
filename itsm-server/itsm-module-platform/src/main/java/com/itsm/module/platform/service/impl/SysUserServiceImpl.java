package com.itsm.module.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.itsm.common.api.PageResult;
import com.itsm.common.exception.BusinessException;
import com.itsm.module.platform.dto.UserCreateCmd;
import com.itsm.module.platform.dto.UserQuery;
import com.itsm.module.platform.dto.UserUpdateCmd;
import com.itsm.module.platform.dto.UserVO;
import com.itsm.module.platform.entity.SysDept;
import com.itsm.module.platform.entity.SysRole;
import com.itsm.module.platform.entity.SysUser;
import com.itsm.module.platform.entity.SysUserRole;
import com.itsm.module.platform.mapper.SysDeptMapper;
import com.itsm.module.platform.mapper.SysRoleMapper;
import com.itsm.module.platform.mapper.SysUserMapper;
import com.itsm.module.platform.mapper.SysUserRoleMapper;
import com.itsm.module.platform.security.SecurityUtils;
import com.itsm.module.platform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.itsm.common.api.ErrorCode.BAD_REQUEST;
import static com.itsm.common.api.ErrorCode.CONFLICT;
import static com.itsm.common.api.ErrorCode.NOT_FOUND;

/** 用户服务实现 */
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl implements SysUserService {

    private final SysUserMapper userMapper;
    private final SysDeptMapper deptMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public PageResult<UserVO> page(UserQuery query) {
        int pageNum = query.pageNum() == null ? 1 : query.pageNum();
        int pageSize = query.pageSize() == null ? 20 : Math.min(query.pageSize(), 200);

        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery();
        if (StringUtils.hasText(query.keyword())) {
            wrapper.and(w -> w.like(SysUser::getUsername, query.keyword())
                    .or().like(SysUser::getNickname, query.keyword())
                    .or().like(SysUser::getEmail, query.keyword())
                    .or().like(SysUser::getPhone, query.keyword()));
        }
        wrapper.eq(query.deptId() != null, SysUser::getDeptId, query.deptId())
                .eq(query.status() != null, SysUser::getStatus, query.status())
                .orderByDesc(SysUser::getId);

        Page<SysUser> page = userMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, toVOs(page.getRecords()));
    }

    @Override
    public UserVO get(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(NOT_FOUND, "用户不存在");
        }
        return toVOs(List.of(user)).get(0);
    }

    @Override
    @Transactional
    public Long create(UserCreateCmd cmd) {
        Long count = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, cmd.username()));
        if (count > 0) {
            throw new BusinessException(CONFLICT, "用户名已存在: " + cmd.username());
        }
        SysUser user = new SysUser();
        user.setUsername(cmd.username());
        user.setPassword(passwordEncoder.encode(cmd.password()));
        user.setNickname(cmd.nickname());
        user.setDeptId(cmd.deptId());
        user.setEmail(cmd.email());
        user.setPhone(cmd.phone());
        user.setStatus(cmd.status() == null ? 1 : cmd.status());
        userMapper.insert(user);
        saveUserRoles(user.getId(), cmd.roleIds());
        return user.getId();
    }

    @Override
    @Transactional
    public void update(Long id, UserUpdateCmd cmd) {
        SysUser exists = requireExists(id);
        SysUser user = new SysUser();
        user.setId(id);
        user.setNickname(cmd.nickname());
        user.setDeptId(cmd.deptId());
        user.setEmail(cmd.email());
        user.setPhone(cmd.phone());
        user.setStatus(cmd.status() == null ? exists.getStatus() : cmd.status());
        userMapper.updateById(user);
        if (cmd.roleIds() != null) {
            saveUserRoles(id, cmd.roleIds());
        }
    }

    /**
     * 删除用户(逻辑删除):
     * 仅将 sys_user.deleted 标记为 true,不物理删除人员记录,也不清理角色关联,
     * 支持后续数据恢复;已删除用户因查询自动过滤 deleted=false 而不可见、不可登录。
     */
    @Override
    @Transactional
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Long currentUserId = SecurityUtils.current().getUserId();
        if (ids.contains(currentUserId)) {
            throw new BusinessException(BAD_REQUEST, "不能删除当前登录用户");
        }
        // 逻辑删除配置下 selectBatchIds 自动过滤已删除记录,可同时完成存在性校验
        List<SysUser> users = userMapper.selectBatchIds(ids.stream().distinct().toList());
        if (users.size() != ids.stream().distinct().count()) {
            throw new BusinessException(NOT_FOUND, "部分用户不存在或已删除");
        }
        if (users.stream().anyMatch(u -> "admin".equals(u.getUsername()))) {
            throw new BusinessException(BAD_REQUEST, "内置管理员不可删除");
        }
        // @TableLogic + 全局逻辑删除配置:deleteByIds 实际执行 UPDATE sys_user SET deleted=true
        userMapper.deleteByIds(ids);
    }

    @Override
    @Transactional
    public void resetPassword(Long id, String newPassword) {
        requireExists(id);
        SysUser user = new SysUser();
        user.setId(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    @Override
    @Transactional
    public void assignRoles(Long id, List<Long> roleIds) {
        requireExists(id);
        saveUserRoles(id, roleIds);
    }

    private SysUser requireExists(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private void saveUserRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        List<Long> ids = roleIds == null ? List.of() : roleIds.stream().filter(Objects::nonNull).distinct().toList();
        for (Long roleId : ids) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }

    private List<UserVO> toVOs(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> deptIds = users.stream().map(SysUser::getDeptId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> deptNames = deptIds.isEmpty() ? Map.of()
                : deptMapper.selectBatchIds(deptIds).stream()
                        .collect(Collectors.toMap(SysDept::getId, SysDept::getName));

        List<Long> userIds = users.stream().map(SysUser::getId).toList();
        Map<Long, List<Long>> roleIdsByUser = userRoleMapper.selectList(
                        Wrappers.<SysUserRole>lambdaQuery().in(SysUserRole::getUserId, userIds))
                .stream().collect(Collectors.groupingBy(SysUserRole::getUserId,
                        Collectors.mapping(SysUserRole::getRoleId, Collectors.toList())));

        List<Long> allRoleIds = roleIdsByUser.values().stream().flatMap(List::stream).distinct().toList();
        Map<Long, String> roleNames = allRoleIds.isEmpty() ? Map.of()
                : roleMapper.selectBatchIds(allRoleIds).stream()
                        .collect(Collectors.toMap(SysRole::getId, SysRole::getName));

        return users.stream().map(u -> {
            List<Long> rids = roleIdsByUser.getOrDefault(u.getId(), Collections.emptyList());
            List<String> rnames = rids.stream().map(rid -> roleNames.get(rid))
                    .filter(Objects::nonNull).toList();
            return new UserVO(u.getId(), u.getUsername(), u.getEmployeeNo(), u.getNickname(), u.getDeptId(),
                    u.getDeptId() == null ? null : deptNames.getOrDefault(u.getDeptId(), "部门已删除"), u.getEmail(), u.getPhone(), u.getStatus(),
                    new ArrayList<>(rids), rnames, u.getCreatedAt());
        }).collect(Collectors.toList());
    }
}

