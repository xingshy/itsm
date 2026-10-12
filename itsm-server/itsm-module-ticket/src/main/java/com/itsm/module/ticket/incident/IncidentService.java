package com.itsm.module.ticket.incident;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsm.common.api.*;
import com.itsm.common.exception.BusinessException;
import com.itsm.module.platform.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All incident writes, relationship checks and append-only audit share one transaction. */
@Service
@RequiredArgsConstructor
public class IncidentService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final LoginUserLoader users;
  static final Set<String> STATES =
      Set.of(
          "DRAFT",
          "PENDING_ASSIGNMENT",
          "PENDING_TAKEOVER",
          "PROCESSING",
          "SUSPENDED",
          "RESOLVED",
          "CLOSED",
          "ARCHIVED");

  JdbcTemplate db() {
    return db;
  }

  LoginUser actor() {
    LoginUser u = users.load(SecurityUtils.current().getUserId());
    if (u == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
    return u;
  }

  void require(boolean ok, ErrorCode code, String message) {
    if (!ok) throw new BusinessException(code, message);
  }

  void permit(LoginUser u, String operation) {
    require(
        u.hasPerm("itsm:incident:" + operation), ErrorCode.FORBIDDEN, "缺少事件" + operation + "权限");
  }

  boolean seat(LoginUser u) {
    return u.getRoles().contains("SEAT");
  }

  boolean canAssign(LoginUser u) {
    return seat(u)
        && count(
                "SELECT count(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id JOIN"
                    + " sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id"
                    + " WHERE ur.user_id=? AND r.status=1 AND r.deleted=false AND m.status=1 AND"
                    + " m.deleted=false AND m.perm='itsm:incident:assign'",
                u.getUserId())
            > 0;
  }

  void assignPermission(LoginUser u) {
    require(seat(u), ErrorCode.FORBIDDEN, "仅启用的服务台角色可以分派工单");
    require(canAssign(u), ErrorCode.FORBIDDEN, "缺少分派权限");
  }

  long count(String sql, Object... args) {
    return Objects.requireNonNull(db.queryForObject(sql, Long.class, args));
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    return db.query(sql, (rs, n) -> row(rs), args);
  }

  Map<String, Object> row(ResultSet rs) throws SQLException {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
      String key = rs.getMetaData().getColumnLabel(i);
      Object v = rs.getObject(i);
      if (v instanceof Timestamp t) v = t.toInstant().toString();
      if (v instanceof OffsetDateTime t) v = t.toInstant().toString();
      if (v != null
          && (key.equals("id")
              || key.endsWith("_id")
              || Set.of("resolved_by", "closed_by", "archived_by").contains(key))) v = v.toString();
      StringBuilder k = new StringBuilder();
      boolean upper = false;
      for (char c : key.toCharArray()) {
        if (c == '_') upper = true;
        else {
          k.append(upper ? Character.toUpperCase(c) : c);
          upper = false;
        }
      }
      m.put(k.toString(), v);
    }
    return m;
  }

  Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> r = rows(sql, args);
    require(!r.isEmpty(), ErrorCode.NOT_FOUND, "记录不存在");
    return r.get(0);
  }

  long id(Map<String, Object> m, String key) {
    return parseLong(Objects.toString(m.get(key), "0"));
  }

  String str(Map<String, Object> m, String key) {
    return Objects.toString(m.get(key), "");
  }

  String text(Map<String, Object> b, String key, int max, boolean mandatory) {
    String s = str(b, key).trim();
    require(
        (!mandatory || !s.isEmpty()) && s.length() <= max,
        ErrorCode.BAD_REQUEST,
        key + "必填且长度不得超过" + max);
    return s;
  }

  String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  Map<String, Object> decode(String s) {
    try {
      return json.readValue(s, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String scope(LoginUser u, List<Object> args) {
    args.add(u.getUserId());
    args.add(u.getUserId());
    args.add(u.getUserId());
    args.add(u.getUserId());
    args.add(u.getUserId());
    args.add(u.getUserId());
    return "(t.type='INCIDENT' AND t.draft_deleted=false AND (t.creator_id=? OR (t.status<>'DRAFT'"
        + " AND (t.owner_id=? OR t.pending_user_id=? OR EXISTS(SELECT 1 FROM"
        + " incident_responsibility rr WHERE rr.ticket_id=t.id AND rr.user_id=?) OR"
        + " EXISTS(SELECT 1 FROM incident_forward f JOIN incident_forward_recipient fr ON"
        + " fr.thread_id=f.id WHERE f.ticket_id=t.id AND f.status IN ('OPEN','ENDED') AND"
        + " fr.user_id=?) OR EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON"
        + " r.id=ur.role_id JOIN sys_user su ON su.id=ur.user_id WHERE ur.user_id=? AND"
        + " r.status=1 AND r.deleted=false AND (r.data_scope='ALL' OR (r.data_scope='DEPT'"
        + " AND (su.dept_id=t.department_id OR su.dept_id=t.report_dept_id))))))))";
  }

  boolean inDataScope(LoginUser u, Map<String, Object> t) {
    return count(
            "SELECT count(*) FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id JOIN sys_user"
                + " su ON su.id=ur.user_id WHERE ur.user_id=? AND r.status=1 AND r.deleted=false"
                + " AND (r.data_scope='ALL' OR (r.data_scope='DEPT' AND (su.dept_id=? OR"
                + " su.dept_id=?)))",
            u.getUserId(),
            nullableId(t, "departmentId"),
            nullableId(t, "reportDeptId"))
        > 0;
  }

  Object nullableId(Map<String, Object> m, String key) {
    return m.get(key) == null ? null : id(m, key);
  }

  Map<String, Object> visible(long incident, LoginUser u, boolean lock) {
    Map<String, Object> t =
        one(
            "SELECT * FROM ticket WHERE id=? AND type='INCIDENT'" + (lock ? " FOR UPDATE" : ""),
            incident);
    List<Object> args = new ArrayList<>();
    args.add(incident);
    String sc = scope(u, args);
    require(
        count("SELECT count(*) FROM ticket t WHERE t.id=? AND " + sc, args.toArray()) > 0,
        ErrorCode.FORBIDDEN,
        "无权访问此事件");
    return t;
  }

  boolean owner(LoginUser u, Map<String, Object> t) {
    return id(t, "ownerId") == u.getUserId();
  }

  boolean creator(LoginUser u, Map<String, Object> t) {
    return id(t, "creatorId") == u.getUserId();
  }

  boolean desk(LoginUser u, Map<String, Object> t) {
    return seat(u) && inDataScope(u, t);
  }

  void state(Map<String, Object> t, String... states) {
    require(
        Arrays.asList(states).contains(str(t, "status")),
        ErrorCode.TICKET_STATE_ILLEGAL,
        "当前状态不允许此操作");
  }

  Map<String, Object> person(long user) {
    return one(
        "SELECT u.id,u.nickname,u.dept_id,d.name AS dept_name FROM sys_user u LEFT JOIN sys_dept d"
            + " ON d.id=u.dept_id AND d.deleted=false AND d.status=1 WHERE u.id=? AND u.status=1"
            + " AND u.deleted=false",
        user);
  }

  int config(String key, int fallback) {
    List<String> r =
        db.queryForList(
            "SELECT config_value FROM sys_config WHERE config_key=?", String.class, key);
    return r.isEmpty() ? fallback : Integer.parseInt(r.get(0));
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> b, String key) {
    LoginUser u = actor();
    permit(u, "create");
    return once(
        u,
        "create",
        b,
        key,
        () -> {
          boolean draft = Boolean.TRUE.equals(b.get("draft"));
          validateBasics(b, !draft);
          Map<String, Object> p = person(u.getUserId());
          long reporter = u.getUserId();
          if (b.get("reporterId") != null && id(b, "reporterId") != reporter) {
            require(seat(u), ErrorCode.FORBIDDEN, "仅服务台可以代录");
            reporter = id(b, "reporterId");
          }
          Map<String, Object> r = person(reporter);
          long ticket =
              db.queryForObject(
                  "INSERT INTO"
                      + " ticket(title,description,category,impact,urgency,priority,creator_id,creator_name,reporter_id,reporter_name,report_dept_id,report_dept_name,source,occurred_at)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                  Long.class,
                  text(b, "title", 200, !draft),
                  text(b, "description", 10000, !draft),
                  str(b, "category"),
                  str(b, "impact"),
                  str(b, "urgency"),
                  priority(b),
                  u.getUserId(),
                  p.get("nickname"),
                  reporter,
                  r.get("nickname"),
                  nullableId(r, "deptId"),
                  r.get("deptName"),
                  reporter == u.getUserId() ? "PORTAL" : "SEAT",
                  occurred(b));
          if (!draft) submitInternal(ticket);
          Map<String, Object> t = one("SELECT * FROM ticket WHERE id=?", ticket);
          action(t, u, draft ? "DRAFT_CREATED" : "CREATED", "DRAFT", Map.of());
          if (!draft) notifySeats(ticket, id(t, "actionId"), "PENDING_ASSIGNMENT");
          return detailInternal(ticket, u);
        });
  }

  void validateBasics(Map<String, Object> b, boolean full) {
    text(b, "title", 200, full);
    text(b, "description", 10000, full);
    if (full) {
      require(
          count(
                  "SELECT count(*) FROM sys_dict WHERE dict_type='incident_category' AND code=?",
                  str(b, "category"))
              > 0,
          ErrorCode.BAD_REQUEST,
          "请选择有效分类");
      require(
          Set.of("WIDE", "PARTIAL", "SINGLE").contains(str(b, "impact"))
              && Set.of("HIGH", "MEDIUM", "LOW").contains(str(b, "urgency")),
          ErrorCode.BAD_REQUEST,
          "请选择影响度和紧急度");
    }
    occurred(b);
  }

  Object occurred(Map<String, Object> b) {
    if (str(b, "occurredAt").isBlank()) return null;
    try {
      Instant t = Instant.parse(str(b, "occurredAt"));
      require(!t.isAfter(Instant.now()), ErrorCode.BAD_REQUEST, "发生时间不得晚于提交时间");
      return Timestamp.from(t);
    } catch (java.time.format.DateTimeParseException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "发生时间格式错误");
    }
  }

  String priority(Map<String, Object> b) {
    int impact = List.of("WIDE", "PARTIAL", "SINGLE").indexOf(str(b, "impact"));
    int urgency = List.of("HIGH", "MEDIUM", "LOW").indexOf(str(b, "urgency"));
    return impact < 0 || urgency < 0
        ? "P4"
        : new String[][] {{"P1", "P2", "P3"}, {"P2", "P3", "P4"}, {"P3", "P4", "P4"}}
            [impact][urgency];
  }

  void submitInternal(long ticket) {
    Map<String, Object> t = one("SELECT * FROM ticket WHERE id=?", ticket);
    validateBasics(t, true);
    List<String> sla =
        db.queryForList(
            "SELECT config_value FROM sys_config WHERE config_key=?",
            String.class,
            "incident.sla." + str(t, "priority"));
    String[] values = (sla.isEmpty() ? "240,2880" : sla.get(0)).split(",");
    Instant now = Instant.now();
    String no =
        "INC-"
            + LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().replace("-", "")
            + "-"
            + String.format("%06d", ticket);
    db.update("INSERT INTO incident_cycle(ticket_id,cycle) VALUES(?,1)", ticket);
    db.update(
        "UPDATE ticket SET"
            + " ticket_no=?,status='PENDING_ASSIGNMENT',submitted_at=?,response_due_at=?,resolution_due_at=?"
            + " WHERE id=?",
        no,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(Long.parseLong(values[0]) * 60)),
        Timestamp.from(now.plusSeconds(Long.parseLong(values[1]) * 60)),
        ticket);
  }

  Map<String, Object> once(
      LoginUser u,
      String route,
      Map<String, Object> b,
      String key,
      Supplier<Map<String, Object>> work) {
    require(
        key != null && !key.isBlank() && key.length() <= 128,
        ErrorCode.BAD_REQUEST,
        "必须提供有效幂等键 Idempotency-Key");
    String fingerprint = route + ":" + encode(new TreeMap<>(b));
    db.update(
        "INSERT INTO incident_request(user_id,request_key,fingerprint) VALUES(?,?,?) ON CONFLICT DO"
            + " NOTHING",
        u.getUserId(),
        key,
        fingerprint);
    Map<String, Object> r =
        one(
            "SELECT * FROM incident_request WHERE user_id=? AND request_key=? FOR UPDATE",
            u.getUserId(),
            key);
    require(fingerprint.equals(r.get("fingerprint")), ErrorCode.CONFLICT, "同一幂等键不能提交不同内容");
    if (r.get("response") != null) return decode(str(r, "response"));
    Map<String, Object> result = work.get();
    db.update(
        "UPDATE incident_request SET response=? WHERE user_id=? AND request_key=?",
        encode(result),
        u.getUserId(),
        key);
    return result;
  }

  @Transactional
  public Map<String, Object> mutate(
      long ticket, String op, Long related, Map<String, Object> b, String key) {
    LoginUser u = actor();
    permit(u, "detail");
    if (op.equals("delete-draft")) {
      Map<String, Object> draft = one("SELECT * FROM ticket WHERE id=?", ticket);
      require(creator(u, draft), ErrorCode.FORBIDDEN, "仅创建人可删除草稿");
    } else visible(ticket, u, false);
    return once(
        u,
        ticket + "/" + op + "/" + related,
        b,
        key,
        () -> {
          Map<String, Object> t = visible(ticket, u, true);
          require(
              b.get("version") != null && id(b, "version") == id(t, "version"),
              ErrorCode.CONFLICT,
              "事件已更新，请刷新后重试");
          String before = str(t, "status");
          Map<String, Object> audit = new LinkedHashMap<>();
          switch (op) {
            case "remind" -> {
              permit(u, "remind");
              state(t, "PENDING_TAKEOVER", "PROCESSING", "SUSPENDED", "RESOLVED");
              require(desk(u, t), ErrorCode.FORBIDDEN, "仅服务台可催办");
              audit.put("reason", text(b, "reason", 10000, true));
            }
            case "edit" -> edit(t, u, b, audit);
            case "submit" -> {
              permit(u, "create");
              state(t, "DRAFT");
              require(creator(u, t), ErrorCode.FORBIDDEN, "仅创建人可提交草稿");
              submitInternal(ticket);
            }
            case "delete-draft" -> {
              permit(u, "edit");
              state(t, "DRAFT");
              require(creator(u, t), ErrorCode.FORBIDDEN, "仅创建人可删除草稿");
              db.update(
                  "UPDATE ticket SET draft_deleted=true,closed_at=now(),closed_by=? WHERE id=?",
                  u.getUserId(),
                  ticket);
              audit.put("reason", "删除草稿（保留审计）");
            }
            case "assign" -> assign(t, u, b, audit, false);
            case "transfer" -> assign(t, u, b, audit, true);
            case "accept", "withdraw", "return-request", "return-decision" ->
                assignment(t, u, b, related, op, audit);
            case "forward" -> forward(t, u, b, audit);
            case "read", "reply", "forward-withdraw" -> thread(t, u, b, related, op, audit);
            case "comment" -> comment(t, u, b, audit);
            case "suspend" -> {
              permit(u, "suspend");
              state(t, "PROCESSING");
              require(owner(u, t), ErrorCode.FORBIDDEN, "仅当前处理人可挂起");
              audit.put("reason", text(b, "reason", 10000, true));
              audit.put("expectedResumeAt", str(b, "expectedResumeAt"));
              audit.put(
                  "pausePolicy",
                  db.queryForList(
                      "SELECT config_value FROM sys_config WHERE"
                          + " config_key='incident.suspend.pause'",
                      String.class));
              require(
                  Boolean.TRUE.equals(b.get("externalCondition")),
                  ErrorCode.BAD_REQUEST,
                  "仅等待必要外部条件可以挂起");
              Instant expected;
              try {
                expected = Instant.parse(str(b, "expectedResumeAt"));
              } catch (Exception e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写预计恢复时间");
              }
              require(expected.isAfter(Instant.now()), ErrorCode.BAD_REQUEST, "预计恢复时间必须晚于当前时间");
              db.update(
                  "UPDATE ticket SET"
                      + " status='SUSPENDED',suspended_at=now(),expected_resume_at=?,suspend_pause=?"
                      + " WHERE id=?",
                  Timestamp.from(expected),
                  pausePolicy(),
                  ticket);
            }
            case "resume" -> {
              permit(u, "suspend");
              state(t, "SUSPENDED");
              require(owner(u, t) || desk(u, t), ErrorCode.FORBIDDEN, "无权解除挂起");
              audit.put("pausedSeconds", resume(t));
              audit.put("reason", text(b, "reason", 10000, true));
            }
            case "resolve" -> resolve(t, u, b, audit);
            case "reject-resolution" -> {
              permit(u, "close");
              state(t, "RESOLVED");
              require(creator(u, t), ErrorCode.FORBIDDEN, "仅创建人可退回处理");
              audit.put("reason", text(b, "reason", 10000, true));
              startCycle(t, "RETURNED");
            }
            case "close" -> close(t, u, b, audit);
            case "archive" -> {
              permit(u, "archive");
              state(t, "CLOSED");
              require(desk(u, t) || u.isAdmin(), ErrorCode.FORBIDDEN, "无权归档");
              audit.put("reason", text(b, "reason", 10000, true));
              require(
                  count(
                          "SELECT count(*) FROM incident_assignment WHERE ticket_id=? AND"
                              + " status='PENDING'",
                          ticket)
                      == 0,
                  ErrorCode.CONFLICT,
                  "存在未完成接管任务");
              db.update(
                  "UPDATE ticket SET status='ARCHIVED',archived_at=now(),archived_by=? WHERE id=?",
                  u.getUserId(),
                  ticket);
              db.update(
                  "UPDATE incident_forward SET status='ENDED',ended_at=now() WHERE ticket_id=? AND"
                      + " status='OPEN'",
                  ticket);
            }
            case "reopen-request" -> {
              permit(u, "close");
              state(t, "CLOSED", "ARCHIVED");
              require(creator(u, t), ErrorCode.FORBIDDEN, "仅创建人可申请重开");
              audit.put("reason", text(b, "reason", 10000, true));
            }
            case "reopen" -> reopen(t, u, b, audit);
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "未知事件动作");
          }
          // Action records use validated data only. Request fields never override the actor.
          t = one("SELECT * FROM ticket WHERE id=?", ticket);
          action(t, u, op.toUpperCase(Locale.ROOT).replace('-', '_'), before, audit);
          long actionId = id(t, "actionId");
          dispatchNotifications(t, u, op, audit, actionId);
          return op.equals("delete-draft")
              ? Map.of("id", Long.toString(ticket), "deleted", true)
              : detailInternal(ticket, u);
        });
  }

  void edit(Map<String, Object> t, LoginUser u, Map<String, Object> b, Map<String, Object> audit) {
    permit(u, "edit");
    state(t, "DRAFT", "PENDING_ASSIGNMENT", "PROCESSING", "SUSPENDED");
    require(
        str(t, "status").equals("DRAFT") ? creator(u, t) : owner(u, t) || desk(u, t),
        ErrorCode.FORBIDDEN,
        "已提交事件仅可补充说明，或由服务台/处理人编辑");
    Set<String> allowed =
        Set.of(
            "version",
            "title",
            "description",
            "category",
            "impact",
            "urgency",
            "occurredAt",
            "priority",
            "reason");
    require(allowed.containsAll(b.keySet()), ErrorCode.BAD_REQUEST, "普通编辑不能修改状态、人员或系统字段");
    Map<String, Object> merged = new LinkedHashMap<>(t);
    merged.putAll(b);
    validateBasics(merged, !str(t, "status").equals("DRAFT"));
    String p =
        b.containsKey("priority")
            ? str(b, "priority")
            : (b.containsKey("impact") || b.containsKey("urgency")
                ? priority(merged)
                : str(t, "priority"));
    require(Set.of("P1", "P2", "P3", "P4").contains(p), ErrorCode.BAD_REQUEST, "优先级无效");
    if (!p.equals(str(t, "priority")) && !str(t, "status").equals("DRAFT")) {
      require(desk(u, t) || u.isAdmin(), ErrorCode.FORBIDDEN, "仅授权服务台可调整优先级");
      audit.put("reason", text(b, "reason", 10000, true));
    }
    if (!p.equals(str(t, "priority")) && !str(t, "status").equals("DRAFT"))
      recalculateSla(t, p, audit);
    for (String k : List.of("title", "description", "category", "impact", "urgency", "occurredAt"))
      if (!Objects.equals(t.get(k), merged.get(k)))
        audit.put(k, Arrays.asList(t.get(k), merged.get(k)));
    if (!p.equals(str(t, "priority"))) audit.put("priority", List.of(str(t, "priority"), p));
    db.update(
        "UPDATE ticket SET"
            + " title=?,description=?,category=?,impact=?,urgency=?,priority=?,occurred_at=? WHERE"
            + " id=?",
        text(merged, "title", 200, false),
        text(merged, "description", 10000, false),
        str(merged, "category"),
        str(merged, "impact"),
        str(merged, "urgency"),
        p,
        occurred(merged),
        id(t, "id"));
  }

  void recalculateSla(Map<String, Object> t, String priority, Map<String, Object> audit) {
    List<String> cfg =
        db.queryForList(
            "SELECT config_value FROM sys_config WHERE config_key=?",
            String.class,
            "incident.sla." + priority);
    String[] minutes = (cfg.isEmpty() ? "240,2880" : cfg.get(0)).split(",");
    long elapsed =
        count(
            "SELECT COALESCE(CAST(SUM(GREATEST(0,EXTRACT(EPOCH FROM"
                + " (COALESCE(c.ended_at,now())-c.started_at))-c.paused_seconds-CASE WHEN"
                + " t.status='SUSPENDED' AND t.suspend_pause AND t.cycle=c.cycle THEN EXTRACT(EPOCH"
                + " FROM (now()-t.suspended_at)) ELSE 0 END)) AS BIGINT),0) FROM incident_cycle c"
                + " JOIN ticket t ON t.id=c.ticket_id WHERE c.ticket_id=?",
            id(t, "id"));
    Instant anchor =
        str(t, "status").equals("SUSPENDED") && Boolean.TRUE.equals(t.get("suspendPause"))
            ? Instant.parse(str(t, "suspendedAt"))
            : Instant.now();
    Instant
        response =
            Instant.parse(str(t, "submittedAt")).plusSeconds(Long.parseLong(minutes[0]) * 60),
        resolution = anchor.plusSeconds(Long.parseLong(minutes[1]) * 60 - elapsed);
    audit.put("responseDueAt", Arrays.asList(t.get("responseDueAt"), response.toString()));
    audit.put("resolutionDueAt", Arrays.asList(t.get("resolutionDueAt"), resolution.toString()));
    db.update(
        "UPDATE ticket SET response_due_at=?,resolution_due_at=? WHERE id=?",
        Timestamp.from(response),
        Timestamp.from(resolution),
        id(t, "id"));
  }

  void assign(
      Map<String, Object> t,
      LoginUser u,
      Map<String, Object> b,
      Map<String, Object> audit,
      boolean transfer) {
    long ticket = id(t, "id");
    if (transfer) {
      permit(u, "transfer");
      state(t, "PROCESSING");
      require(owner(u, t) || desk(u, t), ErrorCode.FORBIDDEN, "无权转交");
      if (owner(u, t)) handled(t, u);
      else audit.put("exceptionCorrection", true);
      audit.put("progress", text(b, "progress", 10000, true));
      audit.put("remaining", text(b, "remaining", 10000, true));
    } else {
      assignPermission(u);
      require(inDataScope(u, t), ErrorCode.FORBIDDEN, "事件不在分派数据范围内");
      state(t, "PENDING_ASSIGNMENT", "PENDING_TAKEOVER", "PROCESSING");
    }
    Map<String, Object> p = person(id(b, "targetUserId"));
    long target = id(p, "id");
    require(
        target != u.getUserId() && (id(t, "ownerId") == 0 || target != id(t, "ownerId")),
        ErrorCode.BAD_REQUEST,
        "不能分配/转交给自己或当前处理人");
    require(
        p.get("deptName") != null && id(p, "deptId") == id(b, "departmentId"),
        ErrorCode.BAD_REQUEST,
        "目标人员不属于所选启用部门");
    String reason = text(b, "reason", 10000, true);
    db.update(
        "UPDATE incident_assignment SET status='REPLACED',ended_at=now() WHERE ticket_id=? AND"
            + " status='PENDING'",
        ticket);
    long task =
        db.queryForObject(
            "INSERT INTO"
                + " incident_assignment(ticket_id,kind,source_user_id,source_user_name,target_user_id,target_user_name,department_id,department_name,initiator_id,reason,progress,remaining,due_at,cycle)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
            Long.class,
            ticket,
            transfer ? "TRANSFER" : "ASSIGN",
            nullableId(t, "ownerId"),
            t.get("ownerName"),
            target,
            p.get("nickname"),
            id(p, "deptId"),
            p.get("deptName"),
            u.getUserId(),
            reason,
            b.get("progress"),
            b.get("remaining"),
            Timestamp.from(
                Instant.now().plusSeconds(config("incident.takeover.minutes", 120) * 60L)),
            t.get("cycle"));
    db.update(
        "UPDATE ticket SET status='PENDING_TAKEOVER',pending_user_id=?,pending_user_name=? WHERE"
            + " id=?",
        target,
        p.get("nickname"),
        ticket);
    audit.put("sourceUserName", t.get("ownerName"));
    audit.put("targetUserName", p.get("nickname"));
    audit.put("sourceDepartmentName", t.get("departmentName"));
    audit.put("targetDepartmentName", p.get("deptName"));
    audit.put("taskId", Long.toString(task));
    audit.put("sourceUserId", t.get("ownerId"));
    audit.put("targetUserId", Long.toString(target));
    audit.put("departmentId", p.get("deptId"));
    audit.put("reason", reason);
  }

  void assignment(
      Map<String, Object> t,
      LoginUser u,
      Map<String, Object> b,
      Long task,
      String op,
      Map<String, Object> audit) {
    state(t, "PENDING_TAKEOVER");
    Map<String, Object> a =
        one("SELECT * FROM incident_assignment WHERE id=? AND ticket_id=?", task, id(t, "id"));
    require("PENDING".equals(a.get("status")), ErrorCode.CONFLICT, "待接管任务已结束");
    long ticket = id(t, "id");
    audit.put("sourceUserName", a.get("sourceUserName"));
    audit.put("targetUserName", a.get("targetUserName"));
    audit.put("targetDepartmentName", a.get("departmentName"));
    audit.put("taskId", task.toString());
    audit.put("sourceUserId", a.get("sourceUserId"));
    audit.put("targetUserId", a.get("targetUserId"));
    switch (op) {
      case "accept" -> {
        permit(u, "accept");
        require(id(a, "targetUserId") == u.getUserId(), ErrorCode.FORBIDDEN, "仅目标人员可接管");
        Map<String, Object> p = person(u.getUserId());
        require(
            id(p, "deptId") == id(a, "departmentId") && p.get("deptName") != null,
            ErrorCode.CONFLICT,
            "目标人员或部门已变化，请服务台重新分配");
        endResponsibility(ticket, "TRANSFERRED");
        db.update(
            "INSERT INTO"
                + " incident_responsibility(ticket_id,user_id,user_name,department_id,department_name,cycle,start_reason)"
                + " VALUES(?,?,?,?,?,?,?)",
            ticket,
            u.getUserId(),
            p.get("nickname"),
            id(p, "deptId"),
            p.get("deptName"),
            t.get("cycle"),
            a.get("kind"));
        db.update(
            "UPDATE ticket SET"
                + " status='PROCESSING',owner_id=?,owner_name=?,department_id=?,department_name=?,pending_user_id=null,pending_user_name=null,first_response_at=COALESCE(first_response_at,now())"
                + " WHERE id=?",
            u.getUserId(),
            p.get("nickname"),
            id(p, "deptId"),
            p.get("deptName"),
            ticket);
        db.update(
            "UPDATE incident_assignment SET status='ACCEPTED',ended_at=now() WHERE id=?", task);
      }
      case "return-request" -> {
        permit(u, "accept");
        require(id(a, "targetUserId") == u.getUserId(), ErrorCode.FORBIDDEN, "仅目标人员可申请退回");
        require(!Boolean.TRUE.equals(a.get("returnRequested")), ErrorCode.CONFLICT, "已存在退回申请");
        String reason = text(b, "reason", 10000, true);
        audit.put("reason", reason);
        db.update(
            "UPDATE incident_assignment SET return_requested=true,return_reason=? WHERE id=?",
            reason,
            task);
      }
      case "return-decision" -> {
        assignPermission(u);
        require(desk(u, t), ErrorCode.FORBIDDEN, "无审批权限");
        require(Boolean.TRUE.equals(a.get("returnRequested")), ErrorCode.CONFLICT, "没有待审批退回申请");
        require(b.get("approved") instanceof Boolean, ErrorCode.BAD_REQUEST, "请选择审批结果");
        audit.put("approved", b.get("approved"));
        audit.put("reason", text(b, "reason", 10000, true));
        if (Boolean.TRUE.equals(b.get("approved"))) withdrawAssignment(t, a, "RETURNED");
        else db.update("UPDATE incident_assignment SET return_requested=false WHERE id=?", task);
      }
      case "withdraw" -> {
        if ("ASSIGN".equals(a.get("kind"))) assignPermission(u);
        else permit(u, "transfer");
        require(
            desk(u, t)
                || ("TRANSFER".equals(a.get("kind")) && id(a, "initiatorId") == u.getUserId()),
            ErrorCode.FORBIDDEN,
            "无权撤回");
        audit.put("reason", text(b, "reason", 10000, true));
        withdrawAssignment(t, a, "WITHDRAWN");
      }
    }
  }

  void withdrawAssignment(Map<String, Object> t, Map<String, Object> a, String status) {
    db.update(
        "UPDATE incident_assignment SET status=?,ended_at=now() WHERE id=?", status, id(a, "id"));
    db.update(
        "UPDATE ticket SET status=?,pending_user_id=null,pending_user_name=null WHERE id=?",
        t.get("ownerId") == null ? "PENDING_ASSIGNMENT" : "PROCESSING",
        id(t, "id"));
  }

  void endResponsibility(long ticket, String why) {
    db.update(
        "UPDATE incident_responsibility SET ended_at=now(),end_reason=? WHERE ticket_id=? AND"
            + " ended_at IS NULL",
        why,
        ticket);
  }

  void handled(Map<String, Object> t, LoginUser u) {
    require(
        count(
                "SELECT count(*) FROM incident_comment c JOIN incident_responsibility r ON"
                    + " r.id=c.responsibility_id WHERE c.ticket_id=? AND c.type='PROCESSING' AND"
                    + " r.user_id=? AND r.ended_at IS NULL",
                id(t, "id"),
                u.getUserId())
            > 0,
        ErrorCode.BAD_REQUEST,
        "请先提交本次接管后的处理意见");
  }

  void forward(
      Map<String, Object> t, LoginUser u, Map<String, Object> b, Map<String, Object> audit) {
    permit(u, "forward");
    state(
        t,
        "PENDING_ASSIGNMENT",
        "PENDING_TAKEOVER",
        "PROCESSING",
        "SUSPENDED",
        "RESOLVED",
        "ARCHIVED");
    require(owner(u, t) || desk(u, t), ErrorCode.FORBIDDEN, "仅服务台或当前处理人可转发");
    require(b.get("recipientIds") instanceof List<?>, ErrorCode.BAD_REQUEST, "请选择接收人");
    Set<Long> recipients = new LinkedHashSet<>();
    for (Object v : (List<?>) b.get("recipientIds")) {
      try {
        recipients.add(Long.parseLong(v.toString()));
      } catch (Exception e) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "接收人无效");
      }
    }
    require(
        !recipients.isEmpty() && recipients.size() <= 100 && !recipients.contains(u.getUserId()),
        ErrorCode.BAD_REQUEST,
        "选择1至100名接收人且不能包含自己");
    String reason = text(b, "reason", 10000, true);
    List<Map<String, Object>> people = recipients.stream().map(this::person).toList();
    long thread =
        db.queryForObject(
            "INSERT INTO incident_forward(ticket_id,initiator_id,initiator_name,reason)"
                + " VALUES(?,?,?,?) RETURNING id",
            Long.class,
            id(t, "id"),
            u.getUserId(),
            u.getNickname(),
            reason);
    for (Map<String, Object> p : people)
      db.update(
          "INSERT INTO incident_forward_recipient(thread_id,user_id,user_name) VALUES(?,?,?)",
          thread,
          id(p, "id"),
          p.get("nickname"));
    audit.put("threadId", Long.toString(thread));
    audit.put("recipientIds", recipients.stream().map(Object::toString).toList());
    audit.put("reason", reason);
  }

  boolean fullThread(LoginUser u, Map<String, Object> t, Map<String, Object> f) {
    return creator(u, t)
        || owner(u, t)
        || desk(u, t)
        || u.isAdmin()
        || id(f, "initiatorId") == u.getUserId();
  }

  Map<String, Object> accessibleThread(Map<String, Object> t, LoginUser u, long thread) {
    Map<String, Object> f =
        one("SELECT * FROM incident_forward WHERE id=? AND ticket_id=?", thread, id(t, "id"));
    require(
        fullThread(u, t, f)
            || (!"WITHDRAWN".equals(f.get("status"))
                && count(
                        "SELECT count(*) FROM incident_forward_recipient WHERE thread_id=? AND"
                            + " user_id=?",
                        thread,
                        u.getUserId())
                    > 0),
        ErrorCode.FORBIDDEN,
        "无权访问此协作线程");
    return f;
  }

  void thread(
      Map<String, Object> t,
      LoginUser u,
      Map<String, Object> b,
      Long thread,
      String op,
      Map<String, Object> audit) {
    Map<String, Object> f = accessibleThread(t, u, thread);
    audit.put("threadId", thread.toString());
    switch (op) {
      case "read" -> {
        require(!"WITHDRAWN".equals(f.get("status")), ErrorCode.CONFLICT, "转发已撤回");
        int changed =
            db.update(
                "UPDATE incident_forward_recipient SET read_at=now() WHERE thread_id=? AND"
                    + " user_id=? AND read_at IS NULL",
                thread,
                u.getUserId());
        require(changed > 0, ErrorCode.CONFLICT, "线程已读或您不是接收人");
      }
      case "reply" -> {
        state(
            t,
            "PENDING_ASSIGNMENT",
            "PENDING_TAKEOVER",
            "PROCESSING",
            "SUSPENDED",
            "RESOLVED",
            "CLOSED");
        require("OPEN".equals(f.get("status")), ErrorCode.CONFLICT, "线程已结束或撤回");
        require(
            count(
                    "SELECT count(*) FROM incident_forward_recipient WHERE thread_id=? AND"
                        + " user_id=?",
                    thread,
                    u.getUserId())
                > 0,
            ErrorCode.FORBIDDEN,
            "仅接收人可回复协作意见");
        long commentId =
            addComment(t, u, "COLLABORATION", text(b, "content", 10000, true), thread, null);
        commentMetadata(t, u, b, commentId, thread, audit);
        audit.put("content", str(b, "content"));
      }
      case "forward-withdraw" -> {
        permit(u, "forward");
        require("OPEN".equals(f.get("status")), ErrorCode.CONFLICT, "线程已结束");
        require(id(f, "initiatorId") == u.getUserId() || desk(u, t), ErrorCode.FORBIDDEN, "无权撤回转发");
        db.update(
            "UPDATE incident_forward SET status='WITHDRAWN',ended_at=now() WHERE id=?", thread);
        audit.put("reason", text(b, "reason", 10000, true));
      }
    }
  }

  void comment(
      Map<String, Object> t, LoginUser u, Map<String, Object> b, Map<String, Object> audit) {
    permit(u, "comment");
    state(t, "PENDING_ASSIGNMENT", "PENDING_TAKEOVER", "PROCESSING", "SUSPENDED", "RESOLVED");
    String type = str(b, "type");
    Object responsibility = null;
    if (type.equals("SUPPLEMENT")) require(creator(u, t), ErrorCode.FORBIDDEN, "仅创建人可补充说明");
    else {
      require(type.equals("PROCESSING"), ErrorCode.BAD_REQUEST, "意见类型无效");
      require(owner(u, t), ErrorCode.FORBIDDEN, "仅当前处理人可提交处理意见");
      state(t, "PROCESSING", "SUSPENDED", "PENDING_TAKEOVER");
      responsibility =
          one(
                  "SELECT id FROM incident_responsibility WHERE ticket_id=? AND ended_at IS NULL",
                  id(t, "id"))
              .get("id");
    }
    String content = text(b, "content", 10000, true);
    long commentId = addComment(t, u, type, content, null, responsibility);
    commentMetadata(t, u, b, commentId, null, audit);
    audit.put("type", type);
    audit.put("content", content);
  }

  long addComment(
      Map<String, Object> t,
      LoginUser u,
      String type,
      String content,
      Object thread,
      Object responsibility) {
    return db.queryForObject(
        "INSERT INTO"
            + " incident_comment(ticket_id,type,content,author_id,author_name,cycle,thread_id,responsibility_id)"
            + " VALUES(?,?,?,?,?,?,?,?) RETURNING id",
        Long.class,
        id(t, "id"),
        type,
        content,
        u.getUserId(),
        u.getNickname(),
        t.get("cycle"),
        thread,
        responsibility == null ? null : Long.parseLong(responsibility.toString()));
  }

  void commentMetadata(
      Map<String, Object> t,
      LoginUser u,
      Map<String, Object> b,
      long comment,
      Long thread,
      Map<String, Object> audit) {
    audit.put("commentId", Long.toString(comment));
    if (b.get("correctsCommentId") != null) {
      Map<String, Object> original =
          one(
              "SELECT * FROM incident_comment WHERE id=? AND ticket_id=?",
              id(b, "correctsCommentId"),
              id(t, "id"));
      require(
          id(original, "authorId") == u.getUserId()
              && Objects.equals(nullableId(original, "threadId"), thread),
          ErrorCode.FORBIDDEN,
          "只能在同一意见线程补充更正自己的意见");
      require(
          !str(original, "type").equals("SOLUTION"),
          ErrorCode.BAD_REQUEST,
          "解决方案须通过退回处理保留原结果并重新解决");
      db.update(
          "UPDATE incident_comment SET corrects_comment_id=? WHERE id=?",
          id(original, "id"),
          comment);
      audit.put("correctsCommentId", original.get("id"));
    }
    if (b.get("attachmentIds") != null) {
      require(b.get("attachmentIds") instanceof List<?>, ErrorCode.BAD_REQUEST, "附件引用格式错误");
      Set<Long> ids = new LinkedHashSet<>();
      for (Object v : (List<?>) b.get("attachmentIds")) ids.add(parseLong(v.toString()));
      require(ids.size() <= 10, ErrorCode.BAD_REQUEST, "单条意见最多引用10个附件");
      for (long attachment : ids) {
        Map<String, Object> a =
            one(
                "SELECT * FROM incident_attachment WHERE id=? AND ticket_id=?",
                attachment,
                id(t, "id"));
        require(
            id(a, "uploaderId") == u.getUserId()
                && Objects.equals(nullableId(a, "threadId"), thread)
                && a.get("commentId") == null,
            ErrorCode.FORBIDDEN,
            "附件不属于本人、所属线程不符或已关联意见");
        db.update("UPDATE incident_attachment SET comment_id=? WHERE id=?", comment, attachment);
      }
      audit.put("attachmentIds", ids.stream().map(Object::toString).toList());
    }
  }

  boolean pausePolicy() {
    List<String> cfg =
        db.queryForList(
            "SELECT config_value FROM sys_config WHERE config_key='incident.suspend.pause'",
            String.class);
    return cfg.isEmpty() || Boolean.parseBoolean(cfg.get(0));
  }

  long resume(Map<String, Object> t) {
    long elapsed =
        Duration.between(Instant.parse(str(t, "suspendedAt")), Instant.now()).getSeconds();
    long paused = Boolean.TRUE.equals(t.get("suspendPause")) ? elapsed : 0;
    db.update(
        "UPDATE ticket SET"
            + " status='PROCESSING',suspended_at=null,expected_resume_at=null,paused_seconds=paused_seconds+?,resolution_due_at=resolution_due_at"
            + " + (? * interval '1 second') WHERE id=?",
        paused,
        paused,
        id(t, "id"));
    db.update(
        "UPDATE incident_cycle SET paused_seconds=paused_seconds+? WHERE ticket_id=? AND cycle=?",
        paused,
        id(t, "id"),
        t.get("cycle"));
    return paused;
  }

  void resolve(
      Map<String, Object> t, LoginUser u, Map<String, Object> b, Map<String, Object> audit) {
    permit(u, "resolve");
    state(t, "PROCESSING");
    require(owner(u, t), ErrorCode.FORBIDDEN, "仅当前处理人可解决");
    handled(t, u);
    for (String field : List.of("analysis", "measures", "result", "verification"))
      audit.put(field, text(b, field, 10000, true));
    long commentId =
        addComment(
            t,
            u,
            "SOLUTION",
            encode(audit),
            null,
            one(
                    "SELECT id FROM incident_responsibility WHERE ticket_id=? AND ended_at IS NULL",
                    id(t, "id"))
                .get("id"));
    commentMetadata(t, u, b, commentId, null, audit);
    db.update(
        "UPDATE ticket SET status='RESOLVED',resolved_at=now(),resolved_by=? WHERE id=?",
        u.getUserId(),
        id(t, "id"));
    db.update(
        "UPDATE incident_cycle SET ended_at=now() WHERE ticket_id=? AND cycle=?",
        id(t, "id"),
        t.get("cycle"));
    endResponsibility(id(t, "id"), "RESOLVED");
  }

  void close(Map<String, Object> t, LoginUser u, Map<String, Object> b, Map<String, Object> audit) {
    permit(u, "close");
    state(t, "RESOLVED", "PENDING_ASSIGNMENT");
    require(creator(u, t) || desk(u, t), ErrorCode.FORBIDDEN, "仅创建人或服务台可关闭");
    audit.put("reason", text(b, "reason", 10000, true));
    audit.put("invalid", str(t, "status").equals("PENDING_ASSIGNMENT"));
    db.update(
        "UPDATE ticket SET status='CLOSED',closed_at=now(),closed_by=? WHERE id=?",
        u.getUserId(),
        id(t, "id"));
    db.update(
        "UPDATE incident_cycle SET ended_at=COALESCE(ended_at,now()) WHERE ticket_id=? AND cycle=?",
        id(t, "id"),
        t.get("cycle"));
    endResponsibility(id(t, "id"), "CLOSED");
  }

  void startCycle(Map<String, Object> t, String why) {
    require(t.get("ownerId") != null, ErrorCode.CONFLICT, "没有历史处理人，请服务台指定新的接管人");
    boolean validOwner =
        count(
                "SELECT count(*) FROM sys_user u JOIN sys_dept d ON d.id=u.dept_id WHERE u.id=? AND"
                    + " u.dept_id=? AND u.status=1 AND u.deleted=false AND d.status=1 AND"
                    + " d.deleted=false",
                id(t, "ownerId"),
                nullableId(t, "departmentId"))
            > 0;
    if (!validOwner && why.equals("RETURNED")) {
      nextCycle(t);
      db.update(
          "UPDATE ticket SET"
              + " status='PENDING_ASSIGNMENT',cycle=cycle+1,owner_id=null,owner_name=null,resolved_at=null,resolved_by=null"
              + " WHERE id=?",
          id(t, "id"));
      return;
    }
    require(validOwner, ErrorCode.CONFLICT, "历史处理人或部门已变化，请服务台选择新的有效接管人");
    Map<String, Object> p = person(id(t, "ownerId"));
    nextCycle(t);
    db.update(
        "UPDATE ticket SET status='PROCESSING',cycle=cycle+1,resolved_at=null,resolved_by=null"
            + " WHERE id=?",
        id(t, "id"));
    db.update(
        "INSERT INTO"
            + " incident_responsibility(ticket_id,user_id,user_name,department_id,department_name,cycle,start_reason)"
            + " VALUES(?,?,?,?,?,?,?)",
        id(t, "id"),
        id(p, "id"),
        p.get("nickname"),
        nullableId(p, "deptId"),
        p.get("deptName"),
        id(t, "cycle") + 1,
        why);
  }

  void nextCycle(Map<String, Object> t) {
    long ticket = id(t, "id");
    Map<String, Object> period =
        one("SELECT * FROM incident_cycle WHERE ticket_id=? AND cycle=?", ticket, t.get("cycle"));
    long waiting =
        period.get("endedAt") == null
            ? 0
            : Math.max(
                0,
                Duration.between(Instant.parse(str(period, "endedAt")), Instant.now())
                    .getSeconds());
    db.update(
        "UPDATE ticket SET resolution_due_at=resolution_due_at+(? * interval '1 second') WHERE"
            + " id=?",
        waiting,
        ticket);
    db.update(
        "INSERT INTO incident_cycle(ticket_id,cycle) VALUES(?,?)", ticket, id(t, "cycle") + 1);
  }

  void reopen(
      Map<String, Object> t, LoginUser u, Map<String, Object> b, Map<String, Object> audit) {
    permit(u, "reopen");
    state(t, "CLOSED", "ARCHIVED");
    require(desk(u, t) || u.isAdmin(), ErrorCode.FORBIDDEN, "仅服务台或管理员可重开");
    audit.put("reason", text(b, "reason", 10000, true));
    if (b.get("targetUserId") != null) {
      assignPermission(u);
      require(inDataScope(u, t), ErrorCode.FORBIDDEN, "事件不在分派范围内");
      nextCycle(t);
      db.update(
          "UPDATE ticket SET"
              + " status='PENDING_ASSIGNMENT',cycle=cycle+1,owner_id=null,owner_name=null,resolved_at=null,resolved_by=null"
              + " WHERE id=?",
          id(t, "id"));
      assign(one("SELECT * FROM ticket WHERE id=?", id(t, "id")), u, b, audit, false);
    } else startCycle(t, "REOPENED");
  }

  void action(
      Map<String, Object> t, LoginUser u, String op, String before, Map<String, Object> details) {
    long ticket = id(t, "id");
    db.update(
        "UPDATE ticket SET version=version+1,action_seq=action_seq+1,updated_at=now() WHERE id=?",
        ticket);
    Map<String, Object> p = person(u.getUserId());
    long a =
        db.queryForObject(
            "INSERT INTO"
                + " ticket_action(ticket_id,seq,action,actor_id,actor_name,actor_dept_name,before_status,after_status,details,cycle)"
                + " SELECT id,action_seq,?,?,?,?,?,?,?,cycle FROM ticket WHERE id=? RETURNING id",
            Long.class,
            op,
            u.getUserId(),
            p.get("nickname"),
            p.get("deptName"),
            before,
            t.get("status"),
            encode(details),
            ticket);
    t.put("actionId", Long.toString(a));
  }

  void notify(long ticket, long action, long user, String kind) {
    if (user > 0)
      db.update(
          "INSERT INTO incident_notification_outbox(ticket_id,user_id,action_id,kind)"
              + " VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
          ticket,
          user,
          action,
          kind);
  }

  void notifySeats(long ticket, long action, String kind) {
    Map<String, Object> t = one("SELECT * FROM ticket WHERE id=?", ticket);
    for (Map<String, Object> p :
        rows(
            "SELECT DISTINCT u.id FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN"
                + " sys_role r ON r.id=ur.role_id WHERE u.status=1 AND u.deleted=false AND"
                + " r.code='SEAT' AND r.status=1 AND r.deleted=false")) {
      LoginUser recipient = users.load(id(p, "id"));
      if (recipient != null && inDataScope(recipient, t)) notify(ticket, action, id(p, "id"), kind);
    }
  }

  void dispatchNotifications(
      Map<String, Object> t, LoginUser u, String op, Map<String, Object> audit, long a) {
    long ticket = id(t, "id");
    if (op.equals("remind"))
      notify(
          ticket,
          a,
          str(t, "status").equals("PENDING_TAKEOVER")
              ? id(t, "pendingUserId")
              : str(t, "status").equals("RESOLVED") ? id(t, "creatorId") : id(t, "ownerId"),
          "REMINDER");
    if (op.equals("reject-resolution") && str(t, "status").equals("PENDING_ASSIGNMENT"))
      notifySeats(ticket, a, "PENDING_ASSIGNMENT");
    if (Set.of("submit", "return-request", "reopen-request").contains(op))
      notifySeats(ticket, a, op);
    if (Set.of("assign", "transfer", "reopen").contains(op))
      notify(ticket, a, id(t, "pendingUserId"), "TAKEOVER");
    if (op.equals("forward"))
      for (Object recipient : (List<?>) audit.get("recipientIds"))
        notify(ticket, a, Long.parseLong(recipient.toString()), "COLLABORATION");
    if (op.equals("reply")) {
      Map<String, Object> f =
          one("SELECT * FROM incident_forward WHERE id=?", id(audit, "threadId"));
      Set<Long> recipients = new HashSet<>(List.of(id(f, "initiatorId"), id(t, "ownerId")));
      for (long recipient : recipients) notify(ticket, a, recipient, "REPLY");
    }
    if (Set.of(
            "accept",
            "resolve",
            "close",
            "archive",
            "reject-resolution",
            "withdraw",
            "return-decision")
        .contains(op)) {
      Set<Long> recipients =
          new HashSet<>(List.of(id(t, "creatorId"), id(t, "ownerId"), id(audit, "sourceUserId")));
      if (audit.get("taskId") != null)
        recipients.add(
            id(
                one("SELECT initiator_id FROM incident_assignment WHERE id=?", id(audit, "taskId")),
                "initiatorId"));
      for (long recipient : recipients) notify(ticket, a, recipient, op);
    }
  }

  public Map<String, Object> detail(long ticket) {
    LoginUser u = actor();
    permit(u, "detail");
    return detailInternal(ticket, u);
  }

  Map<String, Object> detailInternal(long ticket, LoginUser u) {
    Map<String, Object> t = visible(ticket, u, false);
    t.put("allowedActions", allowed(t, u));
    t.put(
        "processingSeconds",
        count(
            "SELECT COALESCE(CAST(SUM(GREATEST(0,EXTRACT(EPOCH FROM"
                + " (COALESCE(c.ended_at,now())-c.started_at))-c.paused_seconds-CASE WHEN"
                + " t.status='SUSPENDED' AND t.suspend_pause AND t.cycle=c.cycle THEN EXTRACT(EPOCH"
                + " FROM (now()-t.suspended_at)) ELSE 0 END)) AS BIGINT),0) FROM incident_cycle c"
                + " JOIN ticket t ON t.id=c.ticket_id WHERE c.ticket_id=?",
            ticket));
    t.put(
        "pendingTask",
        rows("SELECT * FROM incident_assignment WHERE ticket_id=? AND status='PENDING'", ticket)
            .stream()
            .findFirst()
            .orElse(null));
    return t;
  }

  List<String> allowed(Map<String, Object> t, LoginUser u) {
    List<String> a = new ArrayList<>();
    String s = str(t, "status");
    boolean own = owner(u, t), create = creator(u, t), desk = desk(u, t);
    if (s.equals("DRAFT") && create) {
      a.addAll(List.of("edit", "submit", "delete-draft"));
      return a.stream().filter(op -> u.hasPerm("itsm:incident:" + permissionFor(op))).toList();
    }
    boolean assignmentAllowed = canAssign(u);
    if (assignmentAllowed
        && inDataScope(u, t)
        && Set.of("PENDING_ASSIGNMENT", "PENDING_TAKEOVER", "PROCESSING").contains(s))
      a.add("assign");
    if (Set.of(
                "PENDING_ASSIGNMENT",
                "PENDING_TAKEOVER",
                "PROCESSING",
                "SUSPENDED",
                "RESOLVED",
                "ARCHIVED")
            .contains(s)
        && (own || desk)) a.add("forward");
    if (s.equals("PROCESSING") && (own || desk)) a.add("transfer");
    if (desk && Set.of("PENDING_TAKEOVER", "PROCESSING", "SUSPENDED", "RESOLVED").contains(s))
      a.add("remind");
    if (s.equals("PENDING_TAKEOVER")) {
      if (id(t, "pendingUserId") == u.getUserId()) a.addAll(List.of("accept", "return-request"));
      Map<String, Object> task =
          one(
              "SELECT * FROM incident_assignment WHERE ticket_id=? AND status='PENDING'",
              id(t, "id"));
      boolean transferRequest = "TRANSFER".equals(task.get("kind"));
      if ((desk || (transferRequest && id(task, "initiatorId") == u.getUserId()))
          && (transferRequest ? u.hasPerm("itsm:incident:transfer") : assignmentAllowed))
        a.add("withdraw");
      if (desk && assignmentAllowed && Boolean.TRUE.equals(task.get("returnRequested")))
        a.add("return-decision");
    }
    if (Set.of("PENDING_ASSIGNMENT", "PROCESSING", "SUSPENDED").contains(s) && (own || desk))
      a.add("edit");
    if (Set.of("PENDING_ASSIGNMENT", "PENDING_TAKEOVER", "PROCESSING", "SUSPENDED", "RESOLVED")
            .contains(s)
        && create) a.add("supplement");
    if (Set.of("PROCESSING", "SUSPENDED", "PENDING_TAKEOVER").contains(s) && own)
      a.add("processing-comment");
    if (s.equals("PROCESSING") && own) a.addAll(List.of("suspend", "resolve"));
    if (s.equals("SUSPENDED") && (own || desk)) a.add("resume");
    if (s.equals("RESOLVED") && create) a.add("reject-resolution");
    if (Set.of("RESOLVED", "PENDING_ASSIGNMENT").contains(s) && (create || desk)) a.add("close");
    if (s.equals("CLOSED") && (desk || u.isAdmin())) a.add("archive");
    if (Set.of("CLOSED", "ARCHIVED").contains(s)) {
      if (desk || u.isAdmin()) a.add("reopen");
      if (create) a.add("reopen-request");
    }
    return a.stream().filter(op -> u.hasPerm("itsm:incident:" + permissionFor(op))).toList();
  }

  String permissionFor(String op) {
    return switch (op) {
      case "submit" -> "create";
      case "delete-draft" -> "edit";
      case "supplement", "processing-comment" -> "comment";
      case "resume" -> "suspend";
      case "withdraw" -> "detail";
      case "return-request" -> "accept";
      case "return-decision" -> "assign";
      case "reject-resolution", "reopen-request" -> "close";
      default -> op;
    };
  }

  public PageResult<Map<String, Object>> list(Map<String, String> q) {
    LoginUser u = actor();
    permit(u, "list");
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE " + scope(u, args));
    if (q.containsKey("keyword") && !q.get("keyword").isBlank()) {
      where.append(" AND (t.ticket_no ILIKE ? OR t.title ILIKE ? OR t.description ILIKE ?)");
      for (int i = 0; i < 3; i++) args.add("%" + q.get("keyword") + "%");
    }
    for (String field :
        List.of(
            "status", "priority", "category", "creatorId", "departmentId", "ownerId", "reporterId"))
      if (q.containsKey(field) && !q.get(field).isBlank()) {
        String column = field.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
        where.append(" AND t.").append(column).append("=?");
        args.add(field.endsWith("Id") ? parseLong(q.get(field)) : q.get(field));
      }
    for (String field : List.of("from", "to"))
      if (q.containsKey(field) && !q.get(field).isBlank()) {
        where.append(" AND t.created_at ").append(field.equals("from") ? ">=" : "<=").append(" ?");
        try {
          args.add(Timestamp.from(Instant.parse(q.get(field))));
        } catch (Exception e) {
          throw new BusinessException(ErrorCode.BAD_REQUEST, "时间筛选格式错误");
        }
      }
    String tab = q.getOrDefault("tab", "all");
    String condition =
        switch (tab) {
          case "created" -> "t.creator_id=?";
          case "takeover" -> "t.pending_user_id=? AND t.status='PENDING_TAKEOVER'";
          case "processing" ->
              "t.owner_id=? AND t.status IN ('PROCESSING','SUSPENDED','PENDING_TAKEOVER')";
          case "forwarded" ->
              "EXISTS(SELECT 1 FROM incident_forward f JOIN incident_forward_recipient fr ON"
                  + " fr.thread_id=f.id WHERE f.ticket_id=t.id AND fr.user_id=? AND"
                  + " f.status<>'WITHDRAWN')";
          case "history" ->
              "EXISTS(SELECT 1 FROM incident_responsibility rr WHERE rr.ticket_id=t.id AND"
                  + " rr.user_id=?)";
          case "archived" -> "t.status='ARCHIVED'";
          default -> "1=1";
        };
    where.append(" AND ").append(condition);
    if (condition.contains("?")) args.add(u.getUserId());
    if ("true".equals(q.get("overdue")))
      where.append(
          " AND ((t.first_response_at IS NULL AND t.response_due_at<now()) OR ((t.status IN"
              + " ('PENDING_ASSIGNMENT','PENDING_TAKEOVER','PROCESSING') OR (t.status='SUSPENDED'"
              + " AND t.suspend_pause=false)) AND t.resolution_due_at<now()))");
    int page = page(q, "page", 1, 100000), size = page(q, "size", 20, 100);
    long total = count("SELECT count(*) FROM ticket t" + where, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    return PageResult.of(
        total,
        page,
        size,
        rows(
            "SELECT t.* FROM ticket t"
                + where
                + " ORDER BY t.updated_at DESC,t.id DESC LIMIT ? OFFSET ?",
            args.toArray()));
  }

  long parseLong(String s) {
    try {
      return Long.parseLong(s);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "编号格式错误");
    }
  }

  int page(Map<String, String> q, String key, int fallback, int max) {
    long n = parseLong(q.getOrDefault(key, Integer.toString(fallback)));
    require(n >= 1 && n <= max, ErrorCode.BAD_REQUEST, "分页参数无效");
    return (int) n;
  }

  public PageResult<Map<String, Object>> records(long ticket, String kind, Map<String, String> q) {
    LoginUser u = actor();
    permit(u, "detail");
    Map<String, Object> t = visible(ticket, u, false);
    List<Object> args = new ArrayList<>();
    args.add(ticket);
    String table =
        switch (kind) {
          case "activities" -> "ticket_action";
          case "responsibilities" -> "incident_responsibility";
          case "assignments" -> "incident_assignment";
          case "cycles" -> "incident_cycle";
          case "comments" -> "incident_comment";
          default -> throw new BusinessException(ErrorCode.BAD_REQUEST);
        };
    String where = " WHERE ticket_id=?";
    if (kind.equals("comments")) where += " AND type<>'COLLABORATION'";
    if (kind.equals("activities")) {
      // Private collaboration content is served exclusively through the thread endpoint.
      if (!u.hasPerm("itsm:incident:audit"))
        where +=
            " AND action NOT IN"
                + " ('FORWARD','REPLY','READ','FORWARD_WITHDRAW','RETURN_DECISION','ATTACHMENT_ADDED')";
      if (q.containsKey("action") && !q.get("action").isBlank()) {
        where += " AND action=?";
        args.add(q.get("action"));
      }
      if (q.containsKey("actorId") && !q.get("actorId").isBlank()) {
        where += " AND actor_id=?";
        args.add(parseLong(q.get("actorId")));
      }
    }
    int page = page(q, "page", 1, 100000), size = page(q, "size", 20, 100);
    long total = count("SELECT count(*) FROM " + table + where, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    List<Map<String, Object>> data =
        rows(
            "SELECT "
                + (kind.equals("cycles")
                    ? "*, paused_seconds + COALESCE((SELECT CASE WHEN t.status='SUSPENDED' AND"
                        + " t.suspend_pause AND t.cycle=incident_cycle.cycle THEN"
                        + " CAST(EXTRACT(EPOCH FROM (now()-t.suspended_at)) AS BIGINT) ELSE 0 END"
                        + " FROM ticket t WHERE t.id=incident_cycle.ticket_id),0) AS"
                        + " effective_paused_seconds"
                    : "*")
                + " FROM "
                + table
                + where
                + " ORDER BY "
                + (kind.equals("cycles") ? "cycle" : "id")
                + " DESC LIMIT ? OFFSET ?",
            args.toArray());
    if (kind.equals("activities"))
      for (Map<String, Object> r : data) r.put("details", decode(str(r, "details")));
    return PageResult.of(total, page, size, data);
  }

  public PageResult<Map<String, Object>> forwards(long ticket, Map<String, String> q) {
    LoginUser u = actor();
    permit(u, "detail");
    Map<String, Object> t = visible(ticket, u, false);
    List<Object> args = new ArrayList<>();
    args.add(ticket);
    String where = " WHERE f.ticket_id=?";
    if (!(creator(u, t) || owner(u, t) || desk(u, t) || u.isAdmin())) {
      where +=
          " AND (f.initiator_id=? OR (f.status<>'WITHDRAWN' AND EXISTS(SELECT 1 FROM"
              + " incident_forward_recipient fr WHERE fr.thread_id=f.id AND fr.user_id=?)))";
      args.add(u.getUserId());
      args.add(u.getUserId());
    }
    int page = page(q, "page", 1, 100000), size = page(q, "size", 20, 100);
    long total = count("SELECT count(*) FROM incident_forward f" + where, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    List<Map<String, Object>> data =
        rows(
            "SELECT f.* FROM incident_forward f" + where + " ORDER BY f.id DESC LIMIT ? OFFSET ?",
            args.toArray());
    for (Map<String, Object> f : data) {
      boolean full = fullThread(u, t, f);
      f.put(
          "recipients",
          rows(
              "SELECT * FROM incident_forward_recipient WHERE thread_id=?"
                  + (full ? "" : " AND user_id=?"),
              full ? new Object[] {id(f, "id")} : new Object[] {id(f, "id"), u.getUserId()}));
      f.put(
          "canReply",
          "OPEN".equals(f.get("status"))
              && !Set.of("ARCHIVED", "DRAFT").contains(str(t, "status"))
              && count(
                      "SELECT count(*) FROM incident_forward_recipient WHERE thread_id=? AND"
                          + " user_id=?",
                      id(f, "id"),
                      u.getUserId())
                  > 0);
      f.put(
          "canWithdraw",
          "OPEN".equals(f.get("status"))
              && (id(f, "initiatorId") == u.getUserId() || desk(u, t))
              && u.hasPerm("itsm:incident:forward"));
    }
    return PageResult.of(total, page, size, data);
  }

  public PageResult<Map<String, Object>> replies(long ticket, long thread, Map<String, String> q) {
    LoginUser u = actor();
    permit(u, "detail");
    Map<String, Object> t = visible(ticket, u, false), f = accessibleThread(t, u, thread);
    List<Object> args = new ArrayList<>(List.of(ticket, thread));
    String where = " WHERE ticket_id=? AND thread_id=?";
    if (!fullThread(u, t, f)) {
      where += " AND author_id=?";
      args.add(u.getUserId());
    }
    int page = page(q, "page", 1, 100000), size = page(q, "size", 20, 100);
    long total = count("SELECT count(*) FROM incident_comment" + where, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    return PageResult.of(
        total,
        page,
        size,
        rows(
            "SELECT * FROM incident_comment" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            args.toArray()));
  }

  public PageResult<Map<String, Object>> recipients(Map<String, String> q) {
    LoginUser u = actor();
    permit(u, "detail");
    List<Object> args = new ArrayList<>();
    String where = " WHERE u.status=1 AND u.deleted=false AND d.status=1 AND d.deleted=false";
    if (q.containsKey("keyword") && !q.get("keyword").isBlank()) {
      where +=
          " AND (u.nickname ILIKE ? OR u.username ILIKE ? OR u.email ILIKE ? OR u.phone ILIKE ?)";
      for (int i = 0; i < 4; i++) args.add("%" + q.get("keyword") + "%");
    }
    if (q.containsKey("departmentId") && !q.get("departmentId").isBlank()) {
      where += " AND u.dept_id=?";
      args.add(parseLong(q.get("departmentId")));
    }
    if (q.containsKey("roles") && !q.get("roles").isBlank()) {
      String[] role = q.get("roles").split(",");
      require(role.length <= 20, ErrorCode.BAD_REQUEST, "角色过多");
      where +=
          " AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE"
              + " ur.user_id=u.id AND r.status=1 AND r.deleted=false AND r.code IN ("
              + String.join(",", Collections.nCopies(role.length, "?"))
              + "))";
      args.addAll(List.of(role));
    }
    int page = page(q, "page", 1, 100000), size = page(q, "size", 20, 100);
    long total =
        count(
            "SELECT count(*) FROM sys_user u JOIN sys_dept d ON d.id=u.dept_id" + where,
            args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    return PageResult.of(
        total,
        page,
        size,
        rows(
            "SELECT u.id,u.username,u.nickname,u.dept_id,d.name AS dept_name FROM sys_user u JOIN"
                + " sys_dept d ON d.id=u.dept_id"
                + where
                + " ORDER BY u.nickname,u.id LIMIT ? OFFSET ?",
            args.toArray()));
  }

  public Map<String, Object> options() {
    LoginUser u = actor();
    permit(u, "list");
    return Map.of(
        "canAssign",
        canAssign(u),
        "categories",
        rows("SELECT code,label FROM sys_dict WHERE dict_type='incident_category' ORDER BY sort"),
        "departments",
        rows("SELECT id,name FROM sys_dept WHERE status=1 AND deleted=false ORDER BY sort,id"),
        "roles",
        rows("SELECT code,name FROM sys_role WHERE status=1 AND deleted=false ORDER BY sort,id"));
  }

  public PageResult<Map<String, Object>> notifications(Map<String, String> q) {
    LoginUser u = actor();
    permit(u, "list");
    List<Object> args = new ArrayList<>();
    args.add(u.getUserId());
    String where = " WHERE n.user_id=? AND " + scope(u, args);
    if ("true".equals(q.get("unread"))) where += " AND n.read_at IS NULL";
    int page = page(q, "page", 1, 100000), size = page(q, "size", 20, 100);
    long total =
        count(
            "SELECT count(*) FROM incident_notification n JOIN ticket t ON t.id=n.ticket_id"
                + where,
            args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    return PageResult.of(
        total,
        page,
        size,
        rows(
            "SELECT n.*,t.title,t.ticket_no FROM incident_notification n JOIN ticket t ON"
                + " t.id=n.ticket_id"
                + where
                + " ORDER BY n.id DESC LIMIT ? OFFSET ?",
            args.toArray()));
  }

  public PageResult<Map<String, Object>> deliveries(Map<String, String> q) {
    LoginUser u = actor();
    permit(u, "audit");
    List<Object> args = new ArrayList<>();
    String where = " WHERE " + scope(u, args);
    if (q.containsKey("status") && !q.get("status").isBlank()) {
      where += " AND n.status=?";
      args.add(q.get("status"));
    }
    int page = page(q, "page", 1, 100000), size = page(q, "size", 20, 100);
    long total =
        count(
            "SELECT count(*) FROM incident_notification_outbox n JOIN ticket t ON t.id=n.ticket_id"
                + where,
            args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    return PageResult.of(
        total,
        page,
        size,
        rows(
            "SELECT"
                + " n.id,n.ticket_id,n.user_id,n.kind,n.status,n.attempts,n.last_error,n.created_at,n.delivered_at"
                + " FROM incident_notification_outbox n JOIN ticket t ON t.id=n.ticket_id"
                + where
                + " ORDER BY n.id DESC LIMIT ? OFFSET ?",
            args.toArray()));
  }

  @Transactional
  public void readNotification(long notification) {
    LoginUser u = actor();
    Map<String, Object> n =
        one(
            "SELECT * FROM incident_notification WHERE id=? AND user_id=?",
            notification,
            u.getUserId());
    visible(id(n, "ticketId"), u, false);
    db.update(
        "UPDATE incident_notification SET read_at=COALESCE(read_at,now()) WHERE id=?",
        notification);
  }

  public Map<String, Object> counters() {
    LoginUser u = actor();
    permit(u, "list");
    Map<String, Object> result = new LinkedHashMap<>();
    for (String tab : List.of("takeover", "processing", "forwarded"))
      result.put(tab, list(Map.of("tab", tab, "size", "1")).getTotal());
    result.put(
        "confirm",
        count(
            "SELECT count(*) FROM ticket WHERE creator_id=? AND status='RESOLVED'", u.getUserId()));
    result.put(
        "collaborationUnread",
        count(
            "SELECT count(*) FROM incident_forward_recipient fr JOIN incident_forward f ON"
                + " f.id=fr.thread_id WHERE fr.user_id=? AND fr.read_at IS NULL AND"
                + " f.status='OPEN'",
            u.getUserId()));
    return result;
  }
}
