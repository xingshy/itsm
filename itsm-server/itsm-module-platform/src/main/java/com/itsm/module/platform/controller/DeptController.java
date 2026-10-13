package com.itsm.module.platform.controller;

import com.itsm.common.api.Result;
import com.itsm.common.api.PageResult;
import com.itsm.module.platform.dto.DeptNode;
import com.itsm.module.platform.dto.UserQuery;
import com.itsm.module.platform.service.SysUserService;
import com.itsm.module.platform.dto.DeptSaveCmd;
import com.itsm.module.platform.security.RequirePerm;
import com.itsm.module.platform.service.SysDeptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 部门管理接口 */
@RestController
@RequestMapping("/api/v1/depts")
@RequiredArgsConstructor
public class DeptController {

    private final SysDeptService deptService;
    private final SysUserService userService;

    public record LeaderOption(Long id, String username, String employeeNo, String nickname, String email, String phone) {}

    @GetMapping("/leader-options")
    @RequirePerm("sys:dept:list")
    public Result<List<LeaderOption>> leaderOptions(String keyword) {
        return Result.ok(userService.page(new UserQuery(1, 50,
                keyword == null ? null : keyword.trim(), null, 1)).getRecords().stream()
                .map(u -> new LeaderOption(u.id(), u.username(), u.employeeNo(), u.nickname(), u.email(), u.phone()))
                .toList());
    }


    @GetMapping("/{id}/employees")
    @RequirePerm("sys:dept:list")
    public Result<PageResult<LeaderOption>> employees(@PathVariable Long id, Integer pageNum, Integer pageSize) {
        int page = pageNum == null ? 1 : Math.max(1, pageNum);
        int size = pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
        var users = userService.page(new UserQuery(page, size, null, id, null));
        return Result.ok(PageResult.of(users.getTotal(), page, size, users.getRecords().stream()
                .map(u -> new LeaderOption(u.id(), u.username(), u.employeeNo(), u.nickname(), u.email(), u.phone()))
                .toList()));
    }

    @GetMapping("/tree")
    @RequirePerm("sys:dept:list")
    public Result<List<DeptNode>> tree() {
        return Result.ok(deptService.tree());
    }

    @PostMapping
    @RequirePerm("sys:dept:add")
    public Result<String> create(@Valid @RequestBody DeptSaveCmd cmd) {
        return Result.ok(deptService.create(cmd).toString());
    }

    @PutMapping("/{id}")
    @RequirePerm("sys:dept:edit")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DeptSaveCmd cmd) {
        deptService.update(id, cmd);
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    @RequirePerm("sys:dept:delete")
    public Result<Void> delete(@PathVariable Long id) {
        deptService.delete(id);
        return Result.ok(null);
    }
}

