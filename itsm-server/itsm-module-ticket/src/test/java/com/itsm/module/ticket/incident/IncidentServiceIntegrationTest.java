package com.itsm.module.ticket.incident;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsm.common.api.ErrorCode;
import com.itsm.common.exception.BusinessException;
import com.itsm.module.platform.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL tests use a disposable schema and never modify the application's schema. */
@EnabledIfEnvironmentVariable(named = "ITS_INCIDENT_TEST_DB_URL", matches = ".+")
class IncidentServiceIntegrationTest {
  static JdbcTemplate db;
  static IncidentService service;
  static TransactionTemplate tx;
  static String schema;
  static DriverManagerDataSource base;

  @BeforeAll
  static void database() {
    String url = System.getenv("ITS_INCIDENT_TEST_DB_URL");
    String user = System.getenv().getOrDefault("ITS_INCIDENT_TEST_DB_USER", "itsm_app"),
        password = System.getenv().getOrDefault("ITS_INCIDENT_TEST_DB_PASSWORD", "123456");
    base = new DriverManagerDataSource(url, user, password);
    schema = "incident_test_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(base).execute("CREATE SCHEMA " + schema);
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
    db = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    ResourceDatabasePopulator migration = new ResourceDatabasePopulator();
    for (String file : List.of("V1__init.sql", "V2__platform.sql", "V5__incident_management.sql"))
      migration.addScript(
          new FileSystemResource("../itsm-boot/src/main/resources/db/migration/" + file));
    migration.execute(ds);
    db.update("INSERT INTO sys_dept(id,parent_id,name) VALUES(2,1,'IT'),(3,1,'业务')");
    for (long id = 2; id <= 8; id++) {
      db.update(
          "INSERT INTO sys_user(id,username,password,nickname,dept_id) VALUES(?,?,?,?,?)",
          id,
          "test" + id,
          "unused",
          "员工" + id,
          id == 5 ? 3 : 2);
    }
    db.update(
        "INSERT INTO sys_user_role(user_id,role_id)"
            + " VALUES(2,4),(3,3),(4,2),(5,4),(6,3),(7,6),(8,4)");
    LoginUserLoader loader =
        id -> {
          List<Map<String, Object>> people =
              db.queryForList(
                  "SELECT * FROM sys_user WHERE id=? AND status=1 AND deleted=false", id);
          if (people.isEmpty()) return null;
          List<Map<String, Object>> roles =
              db.queryForList(
                  "SELECT r.* FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE"
                      + " ur.user_id=? AND r.status=1 AND r.deleted=false",
                  id);
          Set<String> codes = new HashSet<>();
          roles.forEach(r -> codes.add(r.get("code").toString()));
          Set<String> perms =
              new HashSet<>(
                  db.queryForList(
                      "SELECT DISTINCT m.perm FROM sys_menu m JOIN sys_role_menu rm ON"
                          + " rm.menu_id=m.id JOIN sys_user_role ur ON ur.role_id=rm.role_id JOIN"
                          + " sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.status=1 AND"
                          + " r.deleted=false AND m.status=1 AND m.deleted=false AND m.perm IS NOT"
                          + " NULL",
                      String.class,
                      id));
          return LoginUser.builder()
              .userId(id)
              .nickname(people.get(0).get("nickname").toString())
              .username(people.get(0).get("username").toString())
              .roles(codes)
              .perms(perms)
              .build();
        };
    service = new IncidentService(db, new ObjectMapper(), loader);
  }

  @AfterAll
  static void cleanup() {
    if (base != null && schema != null)
      new JdbcTemplate(base).execute("DROP SCHEMA " + schema + " CASCADE");
  }

  @BeforeEach
  void reset() {
    db.execute("TRUNCATE ticket,incident_request CASCADE");
    db.update("UPDATE sys_role SET status=1");
    db.update("UPDATE sys_user SET status=1");
    db.update("DELETE FROM sys_user_role WHERE user_id=1 AND role_id=2");
    db.update(
        "DELETE FROM sys_role_menu WHERE role_id=4 AND menu_id IN (SELECT id FROM sys_menu WHERE"
            + " perm='itsm:incident:assign')");
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  <T> T as(long actor, Supplier<T> work) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                LoginUser.builder().userId(actor).build(), null));
    return tx.execute(s -> work.get());
  }

  Map<String, Object> create(long actor, boolean draft) {
    return as(
        actor,
        () ->
            service.create(
                new LinkedHashMap<>(
                    Map.of(
                        "title",
                        "网络中断",
                        "description",
                        "办公室网络无法连接",
                        "category",
                        "NETWORK",
                        "impact",
                        "WIDE",
                        "urgency",
                        "HIGH",
                        "draft",
                        draft)),
                UUID.randomUUID().toString()));
  }

  Map<String, Object> act(
      long actor, Map<String, Object> t, String op, Long related, Map<String, Object> body) {
    Map<String, Object> b = new LinkedHashMap<>(body);
    b.put("version", t.get("version"));
    return as(
        actor,
        () -> service.mutate(service.id(t, "id"), op, related, b, UUID.randomUUID().toString()));
  }

  Map<String, Object> assign(Map<String, Object> t, long target) {
    return act(
        4,
        t,
        "assign",
        null,
        Map.of("targetUserId", Long.toString(target), "departmentId", "2", "reason", "分配处理"));
  }

  Map<String, Object> accept(Map<String, Object> t, long actor) {
    return act(
        actor, t, "accept", service.id((Map<String, Object>) t.get("pendingTask"), "id"), Map.of());
  }

  Map<String, Object> processing() {
    return accept(assign(create(2, false), 3), 3);
  }

  Map<String, Object> opinion(Map<String, Object> t, long actor) {
    return act(actor, t, "comment", null, Map.of("type", "PROCESSING", "content", "已定位交换机问题并完成核查"));
  }

  Map<String, Object> solve(Map<String, Object> t, long actor) {
    return act(
        actor,
        t,
        "resolve",
        null,
        Map.of(
            "analysis",
            "交换机异常",
            "measures",
            "重启交换机",
            "result",
            "服务已恢复",
            "verification",
            "申请人网络测试通过"));
  }

  @Test
  void fullLifecycleAndReopenPreserveHistory() {
    Map<String, Object> t = processing();
    assertEquals("3", t.get("ownerId"));
    assertNotNull(t.get("firstResponseAt"));
    t = solve(opinion(t, 3), 3);
    assertEquals("RESOLVED", t.get("status"));
    t = act(2, t, "close", null, Map.of("reason", "确认恢复"));
    t = act(4, t, "archive", null, Map.of("reason", "结案资料齐全"));
    assertEquals("ARCHIVED", t.get("status"));
    Map<String, Object> archived = t;
    assertThrows(BusinessException.class, () -> opinion(archived, 3));
    t = act(1, t, "reopen", null, Map.of("reason", "故障复现"));
    assertEquals("PROCESSING", t.get("status"));
    assertEquals(2, ((Number) t.get("cycle")).intValue());
    assertNotNull(t.get("archivedAt"));
    assertEquals(1, service.count("SELECT count(*) FROM incident_comment WHERE type='SOLUTION'"));
    assertEquals(2, service.count("SELECT count(*) FROM incident_cycle"));
  }

  @Test
  void onlySeatCanAssignEvenWithAdminOrPermission() {
    Map<String, Object> t = create(2, false);
    assertEquals(false, as(1, () -> service.options()).get("canAssign"));
    assertEquals(
        ErrorCode.FORBIDDEN,
        assertThrows(
                BusinessException.class,
                () ->
                    act(
                        1,
                        t,
                        "assign",
                        null,
                        Map.of("targetUserId", "3", "departmentId", "2", "reason", "管理员尝试")))
            .getErrorCode());
    db.update(
        "INSERT INTO sys_role_menu(role_id,menu_id) SELECT 4,id FROM sys_menu WHERE"
            + " perm='itsm:incident:assign'");
    assertThrows(
        BusinessException.class,
        () ->
            act(
                2,
                t,
                "assign",
                null,
                Map.of("targetUserId", "3", "departmentId", "2", "reason", "权限点不足")));
    assertEquals(0, service.count("SELECT count(*) FROM incident_assignment"));
    assertEquals(1, service.count("SELECT count(*) FROM ticket_action"));
    db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(1,2)");
    assertEquals(true, as(1, () -> service.options()).get("canAssign"));
    Map<String, Object> assigned =
        act(
            1,
            t,
            "assign",
            null,
            Map.of("targetUserId", "3", "departmentId", "2", "reason", "兼具坐席"));
    assertEquals("PENDING_TAKEOVER", assigned.get("status"));
    db.update("UPDATE sys_role SET status=0 WHERE code='SEAT'");
    assertThrows(
        BusinessException.class,
        () ->
            act(
                4,
                assigned,
                "assign",
                null,
                Map.of("targetUserId", "6", "departmentId", "2", "reason", "过期权限")));
  }

  @Test
  void transferKeepsOriginalResponsibilityUntilAcceptance() {
    Map<String, Object> t = processing();
    Map<String, Object> before = t;
    assertThrows(
        BusinessException.class,
        () ->
            act(
                3,
                before,
                "transfer",
                null,
                Map.of(
                    "targetUserId",
                    "6",
                    "departmentId",
                    "2",
                    "reason",
                    "移交",
                    "progress",
                    "定位中",
                    "remaining",
                    "修复")));
    t = opinion(t, 3);
    String due = service.str(t, "resolutionDueAt");
    t =
        act(
            3,
            t,
            "transfer",
            null,
            Map.of(
                "targetUserId",
                "6",
                "departmentId",
                "2",
                "reason",
                "移交",
                "progress",
                "已定位",
                "remaining",
                "修复"));
    assertEquals("3", t.get("ownerId"));
    assertEquals("6", t.get("pendingUserId"));
    assertEquals(
        1, service.count("SELECT count(*) FROM incident_responsibility WHERE ended_at IS NULL"));
    t = accept(t, 6);
    assertEquals("6", t.get("ownerId"));
    assertEquals(due, t.get("resolutionDueAt"));
    Map<String, Object> accepted = t;
    assertThrows(BusinessException.class, () -> solve(accepted, 6));
    assertEquals(2, service.count("SELECT count(*) FROM incident_responsibility"));
  }

  @Test
  void withdrawalAndApprovedReturnRestoreOwner() {
    Map<String, Object> t = opinion(processing(), 3);
    t =
        act(
            3,
            t,
            "transfer",
            null,
            Map.of(
                "targetUserId",
                "6",
                "departmentId",
                "2",
                "reason",
                "移交",
                "progress",
                "已定位",
                "remaining",
                "修复"));
    long task = service.id((Map<String, Object>) t.get("pendingTask"), "id");
    t = act(6, t, "return-request", task, Map.of("reason", "需原处理组修复"));
    t = act(4, t, "return-decision", task, Map.of("reason", "同意", "approved", true));
    assertEquals("PROCESSING", t.get("status"));
    assertEquals("3", t.get("ownerId"));
    assertNull(t.get("pendingTask"));
    assertEquals(
        "RETURNED",
        service.one("SELECT * FROM incident_assignment WHERE id=?", task).get("status"));
  }

  @Test
  void collaborationIsolationAndWithdrawal() {
    Map<String, Object> t = processing();
    t =
        act(
            3,
            t,
            "forward",
            null,
            Map.of("recipientIds", List.of("5", "8", "5"), "reason", "协助检查"));
    long thread = service.id(service.one("SELECT id FROM incident_forward"), "id");
    t = act(5, t, "reply", thread, Map.of("content", "仅接收人5的回复"));
    Map<String, Object> t1 = t;
    assertThrows(BusinessException.class, () -> solve(t1, 5));
    assertEquals(
        0, as(8, () -> service.replies(service.id(t1, "id"), thread, Map.of())).getTotal());
    assertEquals(
        1, as(5, () -> service.replies(service.id(t1, "id"), thread, Map.of())).getTotal());
    assertEquals(2, service.count("SELECT count(*) FROM incident_forward_recipient"));
    t = act(3, t, "forward-withdraw", thread, Map.of("reason", "协作结束"));
    Map<String, Object> withdrawn = t;
    assertThrows(
        BusinessException.class, () -> as(5, () -> service.detail(service.id(withdrawn, "id"))));
    assertEquals(
        1, service.count("SELECT count(*) FROM incident_comment WHERE type='COLLABORATION'"));
  }

  @Test
  void draftsPrivateAndSoftDeletion() {
    Map<String, Object> draft = create(2, true);
    assertNull(draft.get("ticketNo"));
    assertNull(draft.get("responseDueAt"));
    assertThrows(
        BusinessException.class, () -> as(1, () -> service.detail(service.id(draft, "id"))));
    Map<String, Object> edited = act(2, draft, "edit", null, Map.of("title", "修改草稿"));
    Map<String, Object> submitted = act(2, edited, "submit", null, Map.of());
    assertEquals("PENDING_ASSIGNMENT", submitted.get("status"));
    assertNotNull(submitted.get("ticketNo"));
    Map<String, Object> deleted = create(2, true);
    act(2, deleted, "delete-draft", null, Map.of());
    assertEquals(1, as(2, () -> service.list(Map.of())).getTotal());
    assertEquals(2, service.count("SELECT count(*) FROM ticket"));
  }

  @Test
  void idempotencyAndOptimisticLock() {
    Map<String, Object> b =
        Map.of(
            "title",
            "重试",
            "description",
            "网络异常",
            "category",
            "NETWORK",
            "impact",
            "SINGLE",
            "urgency",
            "LOW");
    String k = UUID.randomUUID().toString();
    Map<String, Object> t = as(2, () -> service.create(b, k));
    assertEquals(service.encode(t), service.encode(as(2, () -> service.create(b, k))));
    assertEquals(1, service.count("SELECT count(*) FROM ticket"));
    assertThrows(
        BusinessException.class, () -> as(2, () -> service.create(Map.of("title", "不同"), k)));
    Map<String, Object> changed = assign(t, 3);
    assertThrows(
        BusinessException.class,
        () ->
            act(
                4,
                t,
                "assign",
                null,
                Map.of("targetUserId", "6", "departmentId", "2", "reason", "旧页面")));
    assertEquals(1, service.count("SELECT count(*) FROM incident_assignment"));
    assertEquals("PENDING_TAKEOVER", changed.get("status"));
  }

  @Test
  void concurrentAcceptAndWithdrawalOnlyOneSucceeds() throws Exception {
    Map<String, Object> t = assign(create(2, false), 3);
    long task = service.id((Map<String, Object>) t.get("pendingTask"), "id");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results =
          List.of(
              pool.submit(
                  () -> {
                    start.await();
                    try {
                      accept(t, 3);
                      return true;
                    } catch (BusinessException e) {
                      return false;
                    } finally {
                      SecurityContextHolder.clearContext();
                    }
                  }),
              pool.submit(
                  () -> {
                    start.await();
                    try {
                      act(4, t, "withdraw", task, Map.of("reason", "撤回"));
                      return true;
                    } catch (BusinessException e) {
                      return false;
                    } finally {
                      SecurityContextHolder.clearContext();
                    }
                  }));
      start.countDown();
      assertEquals(
          1,
          results.stream()
              .map(
                  r -> {
                    try {
                      return r.get(10, TimeUnit.SECONDS) ? 1 : 0;
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .mapToInt(Integer::intValue)
              .sum());
      assertEquals(3, service.count("SELECT count(*) FROM ticket_action"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void inactiveRecipientsAndIllegalFieldUpdatesAreRejected() {
    Map<String, Object> t = create(2, false);
    db.update("UPDATE sys_user SET status=0 WHERE id=3");
    assertThrows(BusinessException.class, () -> assign(t, 3));
    assertThrows(BusinessException.class, () -> act(4, t, "edit", null, Map.of("ownerId", "6")));
    assertEquals(0, service.count("SELECT count(*) FROM incident_assignment"));
  }

  @Test
  void longIdsNeverLosePrecision() {
    db.execute("SELECT setval(pg_get_serial_sequence('ticket','id'),9007199254740993)");
    Map<String, Object> t = create(2, false);
    assertInstanceOf(String.class, t.get("id"));
    assertTrue(Long.parseLong(t.get("id").toString()) > 9007199254740991L);
    t = accept(assign(t, 3), 3);
    assertEquals("3", t.get("ownerId"));
  }

  @Test
  void reminderDeliveryDeduplicatesAndSurvivesRetry() {
    Map<String, Object> t = assign(create(2, false), 3);
    db.update("UPDATE incident_assignment SET due_at=now()-interval '1 hour'");
    db.update(
        "UPDATE ticket SET response_due_at=now()-interval '1 hour',resolution_due_at=now()-interval"
            + " '1 hour'");
    IncidentReminderJob job = new IncidentReminderJob(service);
    tx.executeWithoutResult(s -> job.remind());
    long n = service.count("SELECT count(*) FROM incident_notification_outbox");
    tx.executeWithoutResult(s -> job.remind());
    assertEquals(n, service.count("SELECT count(*) FROM incident_notification_outbox"));
    assertTrue(n > 0);
    IncidentNotificationDelivery delivery = new IncidentNotificationDelivery(service);
    for (Map<String, Object> item : service.rows("SELECT id FROM incident_notification_outbox"))
      tx.executeWithoutResult(s -> delivery.deliver(service.id(item, "id")));
    assertEquals(n, service.count("SELECT count(*) FROM incident_notification"));
    assertEquals(3, service.count("SELECT count(*) FROM incident_reminder"));
    assertEquals(
        "PENDING_TAKEOVER",
        service.one("SELECT * FROM ticket WHERE id=?", service.id(t, "id")).get("status"));
  }

  @Test
  void archivedForwardCanBeReadButNeverReply() {
    Map<String, Object> t = solve(opinion(processing(), 3), 3);
    t = act(2, t, "close", null, Map.of("reason", "恢复"));
    t = act(4, t, "archive", null, Map.of("reason", "归档"));
    t = act(4, t, "forward", null, Map.of("recipientIds", List.of("5"), "reason", "供复盘参考"));
    long thread = service.id(service.one("SELECT id FROM incident_forward"), "id");
    Map<String, Object> archived = t;
    assertEquals("ARCHIVED", as(5, () -> service.detail(service.id(archived, "id"))).get("status"));
    assertThrows(
        BusinessException.class,
        () -> act(5, archived, "reply", thread, Map.of("content", "不允许写入")));
  }

  @Test
  void suspendResumeAndCyclesCountPausedTimeSeparately() {
    Map<String, Object> t = opinion(processing(), 3);
    t =
        act(
            3,
            t,
            "suspend",
            null,
            Map.of(
                "reason",
                "等待供应商",
                "externalCondition",
                true,
                "expectedResumeAt",
                java.time.Instant.now().plusSeconds(3600).toString()));
    long ticket = service.id(t, "id");
    db.update("UPDATE ticket SET suspended_at=now()-interval '10 minutes' WHERE id=?", ticket);
    t = as(3, () -> service.detail(ticket));
    long paused =
        as(3, () -> service.records(ticket, "cycles", Map.of())).getRecords().stream()
            .mapToLong(r -> ((Number) r.get("effectivePausedSeconds")).longValue())
            .sum();
    assertTrue(paused >= 599);
    t = act(3, t, "resume", null, Map.of("reason", "外部条件已满足"));
    assertEquals("PROCESSING", t.get("status"));
    assertTrue(((Number) t.get("pausedSeconds")).longValue() >= 599);
  }

  @Test
  void newOwnerOnReopenMustUseSeatEvenForAdmin() {
    Map<String, Object> t = solve(opinion(processing(), 3), 3);
    t = act(2, t, "close", null, Map.of("reason", "确认"));
    Map<String, Object> closed = t;
    assertEquals(
        ErrorCode.FORBIDDEN,
        assertThrows(
                BusinessException.class,
                () ->
                    act(
                        1,
                        closed,
                        "reopen",
                        null,
                        Map.of("targetUserId", "6", "departmentId", "2", "reason", "指定新人员")))
            .getErrorCode());
    t =
        act(
            4,
            t,
            "reopen",
            null,
            Map.of("targetUserId", "6", "departmentId", "2", "reason", "指定新人员"));
    assertEquals("PENDING_TAKEOVER", t.get("status"));
    assertNull(t.get("ownerId"));
    t = accept(t, 6);
    assertEquals("6", t.get("ownerId"));
  }

  @Test
  void attachmentRetriesAndThreadAccessAreGuarded() {
    Map<String, Object> t = processing();
    t = act(3, t, "forward", null, Map.of("recipientIds", List.of("5", "8"), "reason", "协助"));
    long thread = service.id(service.one("SELECT id FROM incident_forward"), "id"),
        ticket = service.id(t, "id"),
        version = service.id(t, "version");
    com.itsm.common.spi.storage.ObjectStorage storage =
        org.mockito.Mockito.mock(com.itsm.common.spi.storage.ObjectStorage.class);
    org.mockito.Mockito.when(storage.put(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new com.itsm.common.spi.storage.StoredFile(
                "test-key", "note.txt", "application/octet-stream", 4, "unused"));
    org.mockito.Mockito.when(storage.get("test-key"))
        .thenAnswer(x -> new java.io.ByteArrayInputStream("test".getBytes()));
    IncidentAttachmentController attachments = new IncidentAttachmentController(service, storage);
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "files", "note.txt", "text/plain", "test".getBytes());
    String key = UUID.randomUUID().toString();
    Supplier<Object> upload =
        () -> {
          try {
            return attachments.upload(ticket, List.of(file), version, thread, key).getData();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    Object result = as(5, upload);
    assertEquals(service.encode(result), service.encode(as(5, upload)));
    assertEquals(1, service.count("SELECT count(*) FROM incident_attachment"));
    long attachment = service.id(service.one("SELECT id FROM incident_attachment"), "id");
    assertThrows(
        BusinessException.class, () -> as(8, () -> attachments.download(ticket, attachment)));
    assertNotNull(as(5, () -> attachments.download(ticket, attachment)));
    t = as(3, () -> service.detail(ticket));
    act(3, t, "forward-withdraw", thread, Map.of("reason", "结束"));
    assertThrows(
        BusinessException.class, () -> as(5, () -> attachments.download(ticket, attachment)));
  }

  @Test
  void failedNotificationDeliveryNeverRollsBackBusinessAndCanRetry() {
    Map<String, Object> t = create(2, false);
    long ticket = service.id(t, "id");
    IncidentNotificationDelivery delivery =
        org.mockito.Mockito.spy(new IncidentNotificationDelivery(service));
    org.mockito.Mockito.doThrow(new IllegalStateException("simulated transport failure"))
        .doCallRealMethod()
        .when(delivery)
        .deliver(org.mockito.ArgumentMatchers.anyLong());
    IncidentNotificationJob job = new IncidentNotificationJob(service, delivery);
    job.deliverPending();
    assertEquals(
        "PENDING_ASSIGNMENT", service.one("SELECT * FROM ticket WHERE id=?", ticket).get("status"));
    assertEquals(
        1,
        service.count(
            "SELECT count(*) FROM incident_notification_outbox WHERE status='PENDING' AND"
                + " attempts=1"));
    db.update("UPDATE incident_notification_outbox SET next_attempt_at=now()");
    tx.executeWithoutResult(s -> job.deliverPending());
    long n = service.count("SELECT count(*) FROM incident_notification");
    assertTrue(n > 0);
    tx.executeWithoutResult(s -> job.deliverPending());
    assertEquals(n, service.count("SELECT count(*) FROM incident_notification"));
  }

  @Test
  void correctionsAppendAndCannotRewriteOthersOpinions() {
    Map<String, Object> t = opinion(processing(), 3);
    long original =
        service.id(service.one("SELECT id FROM incident_comment WHERE type='PROCESSING'"), "id");
    String before =
        service.str(
            service.one("SELECT content FROM incident_comment WHERE id=?", original), "content");
    t =
        act(
            3,
            t,
            "comment",
            null,
            Map.of(
                "type",
                "PROCESSING",
                "content",
                "补充：交换机端口故障",
                "correctsCommentId",
                Long.toString(original)));
    assertEquals(2, service.count("SELECT count(*) FROM incident_comment"));
    assertEquals(
        before,
        service.one("SELECT content FROM incident_comment WHERE id=?", original).get("content"));
    assertEquals(
        1,
        service.count(
            "SELECT count(*) FROM incident_comment WHERE corrects_comment_id=?", original));
    Map<String, Object> latest = t;
    assertThrows(
        BusinessException.class,
        () ->
            act(
                2,
                latest,
                "comment",
                null,
                Map.of(
                    "type",
                    "SUPPLEMENT",
                    "content",
                    "不能更正他人记录",
                    "correctsCommentId",
                    Long.toString(original))));
    assertEquals(2, service.count("SELECT count(*) FROM incident_comment"));
  }

  @Test
  void priorityAdjustmentsRecalculateSlaAndReminderDoesNotChangeOwner() {
    Map<String, Object> t =
        as(
            2,
            () ->
                service.create(
                    Map.of(
                        "title",
                        "账号异常",
                        "description",
                        "无法登录",
                        "category",
                        "ACCOUNT",
                        "impact",
                        "SINGLE",
                        "urgency",
                        "LOW"),
                    UUID.randomUUID().toString()));
    Map<String, Object> original = t;
    assertThrows(
        BusinessException.class, () -> act(4, original, "edit", null, Map.of("priority", "P1")));
    t = act(4, t, "edit", null, Map.of("priority", "P1", "reason", "业务范围扩大"));
    assertEquals("P1", t.get("priority"));
    assertTrue(
        java.time.Instant.parse(service.str(t, "resolutionDueAt"))
            .isBefore(java.time.Instant.now().plusSeconds(14500)));
    t = assign(t, 3);
    long task = service.id((Map<String, Object>) t.get("pendingTask"), "id");
    t = act(4, t, "remind", null, Map.of("reason", "请及时接管"));
    assertEquals("PENDING_TAKEOVER", t.get("status"));
    assertEquals(task, service.id((Map<String, Object>) t.get("pendingTask"), "id"));
    assertEquals(
        1,
        service.count("SELECT count(*) FROM incident_notification_outbox WHERE kind='REMINDER'"));
  }

  @Test
  void inactiveHistoricalOwnerReturnsToDeskInsteadOfStrandingEvent() {
    Map<String, Object> t = solve(opinion(processing(), 3), 3);
    db.update("UPDATE sys_user SET status=0 WHERE id=3");
    t = act(2, t, "reject-resolution", null, Map.of("reason", "未恢复且原处理人已停用"));
    assertEquals("PENDING_ASSIGNMENT", t.get("status"));
    assertNull(t.get("ownerId"));
    assertEquals(2, ((Number) t.get("cycle")).intValue());
    t = accept(assign(t, 6), 6);
    assertEquals("6", t.get("ownerId"));
  }
}
