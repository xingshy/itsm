package com.itsm.module.ticket.incident;

import com.itsm.common.api.*;
import com.itsm.common.spi.storage.*;
import com.itsm.module.platform.security.LoginUser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/incidents/{id}/attachments")
@RequiredArgsConstructor
public class IncidentAttachmentController {
  private final IncidentService service;
  private final ObjectStorage storage;
  private static final Set<String> EXTENSIONS =
      Set.of(
          "pdf", "txt", "csv", "png", "jpg", "jpeg", "gif", "docx", "xlsx", "pptx", "log", "zip");

  @GetMapping
  public Result<?> list(@PathVariable long id, @RequestParam Map<String, String> query) {
    LoginUser u = service.actor();
    service.permit(u, "detail");
    Map<String, Object> t = service.visible(id, u, false);
    Long thread = query.containsKey("threadId") ? service.parseLong(query.get("threadId")) : null;
    boolean full =
        thread == null || service.fullThread(u, t, service.accessibleThread(t, u, thread));
    List<Object> args = new ArrayList<>(List.of(id));
    String where =
        " WHERE ticket_id=? AND " + (thread == null ? "thread_id IS NULL" : "thread_id=?");
    if (thread != null) args.add(thread);
    if (!full) {
      where += " AND uploader_id=?";
      args.add(u.getUserId());
    }
    int page = service.page(query, "page", 1, 100000), size = service.page(query, "size", 20, 100);
    long count = service.count("SELECT count(*) FROM incident_attachment" + where, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    List<Map<String, Object>> data =
        service.rows(
            "SELECT"
                + " id,ticket_id,thread_id,uploader_id,comment_id,filename,content_type,size,created_at"
                + " FROM incident_attachment"
                + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?",
            args.toArray());
    return Result.ok(PageResult.of(count, page, size, data));
  }

  @PostMapping
  @Transactional
  public Result<?> upload(
      @PathVariable long id,
      @RequestParam List<MultipartFile> files,
      @RequestParam long version,
      @RequestParam(required = false) Long threadId,
      @RequestHeader("Idempotency-Key") String key)
      throws Exception {
    LoginUser u = service.actor();
    service.permit(u, "comment");
    service.visible(id, u, false);
    service.require(
        !files.isEmpty() && files.size() <= service.config("incident.attachment.max-count", 10),
        ErrorCode.BAD_REQUEST,
        "超过系统配置的单次附件数量上限");
    List<String> extensionConfig =
        service
            .db()
            .queryForList(
                "SELECT config_value FROM sys_config WHERE"
                    + " config_key='incident.attachment.extensions'",
                String.class);
    Set<String> allowed =
        extensionConfig.isEmpty()
            ? EXTENSIONS
            : new HashSet<>(
                Arrays.asList(extensionConfig.get(0).toLowerCase(Locale.ROOT).split(",")));
    List<Map<String, Object>> signature = new ArrayList<>();
    for (MultipartFile file : files) {
      String name = Objects.toString(file.getOriginalFilename(), "").replace('\\', '/');
      name = name.substring(name.lastIndexOf('/') + 1);
      service.require(
          name.length() > 0
              && name.length() <= 255
              && name.contains(".")
              && allowed.contains(
                  name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))
              && file.getSize() > 0
              && file.getSize() <= service.config("incident.attachment.max-mb", 20) * 1024L * 1024,
          ErrorCode.BAD_REQUEST,
          "附件类型不支持、为空或超过系统大小限制");
      byte[] hash;
      try (var stream = file.getInputStream()) {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] chunk = new byte[8192];
        int n;
        while ((n = stream.read(chunk)) != -1) digest.update(chunk, 0, n);
        hash = digest.digest();
      }
      signature.add(
          Map.of(
              "filename", name, "sha256", HexFormat.of().formatHex(hash), "size", file.getSize()));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("files", signature);
    body.put("version", version);
    body.put("threadId", threadId);
    return Result.ok(
        service.once(
            u,
            id + "/attachments",
            body,
            key,
            () -> {
              Map<String, Object> t = service.visible(id, u, true);
              service.state(
                  t,
                  "DRAFT",
                  "PENDING_ASSIGNMENT",
                  "PENDING_TAKEOVER",
                  "PROCESSING",
                  "SUSPENDED",
                  "RESOLVED");
              service.require(
                  service.id(t, "version") == version, ErrorCode.CONFLICT, "事件已更新，请刷新后重试");
              if (threadId == null)
                service.require(
                    service.creator(u, t) || service.owner(u, t) || service.desk(u, t),
                    ErrorCode.FORBIDDEN,
                    "无权上传事件附件");
              else {
                Map<String, Object> f = service.accessibleThread(t, u, threadId);
                service.require("OPEN".equals(f.get("status")), ErrorCode.CONFLICT, "协作线程已结束");
                service.require(
                    service.id(f, "initiatorId") == u.getUserId()
                        || service.count(
                                "SELECT count(*) FROM incident_forward_recipient WHERE thread_id=?"
                                    + " AND user_id=?",
                                threadId,
                                u.getUserId())
                            > 0,
                    ErrorCode.FORBIDDEN,
                    "无权上传协作附件");
              }

              long incoming = files.stream().mapToLong(MultipartFile::getSize).sum();
              service.require(
                  service.count(
                              "SELECT COALESCE(sum(size),0) FROM incident_attachment WHERE"
                                  + " ticket_id=?",
                              id)
                          + incoming
                      <= service.config("incident.attachment.quota-mb", 200) * 1024L * 1024,
                  ErrorCode.BAD_REQUEST,
                  "超过事件附件总配额");
              List<String> keys = new ArrayList<>();
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                      if (status != STATUS_COMMITTED)
                        for (String stored : keys)
                          try {
                            storage.remove(stored);
                          } catch (Exception ignored) {
                          }
                    }
                  });
              List<String> attachments = new ArrayList<>();
              try {
                for (int i = 0; i < files.size(); i++) {
                  MultipartFile file = files.get(i);
                  String name = signature.get(i).get("filename").toString();
                  try (FileUpload upload =
                      new FileUpload(
                          name,
                          "application/octet-stream",
                          file.getSize(),
                          file.getInputStream())) {
                    StoredFile stored = storage.put(upload);
                    keys.add(stored.key());
                    long attachment =
                        service
                            .db()
                            .queryForObject(
                                "INSERT INTO"
                                    + " incident_attachment(ticket_id,thread_id,uploader_id,filename,content_type,storage_key,size)"
                                    + " VALUES(?,?,?,?,?,?,?) RETURNING id",
                                Long.class,
                                id,
                                threadId,
                                u.getUserId(),
                                name,
                                "application/octet-stream",
                                stored.key(),
                                file.getSize());
                    attachments.add(Long.toString(attachment));
                  }
                }
              } catch (Exception e) {
                throw new IllegalStateException("附件保存失败", e);
              }
              service.action(
                  t,
                  u,
                  "ATTACHMENT_ADDED",
                  service.str(t, "status"),
                  Map.of(
                      "attachmentIds",
                      attachments,
                      "threadId",
                      threadId == null ? "" : threadId.toString()));
              for (String attachment : attachments)
                service
                    .db()
                    .update(
                        "UPDATE incident_attachment SET action_id=? WHERE id=?",
                        service.id(t, "actionId"),
                        Long.parseLong(attachment));
              return Map.of("attachmentIds", attachments);
            }));
  }

  @GetMapping("/{attachmentId}")
  public ResponseEntity<InputStreamResource> download(
      @PathVariable long id, @PathVariable long attachmentId) {
    LoginUser u = service.actor();
    service.permit(u, "detail");
    Map<String, Object> t = service.visible(id, u, false);
    Map<String, Object> a =
        service.one(
            "SELECT * FROM incident_attachment WHERE id=? AND ticket_id=?", attachmentId, id);
    if (a.get("threadId") != null) {
      Map<String, Object> f = service.accessibleThread(t, u, service.id(a, "threadId"));
      service.require(
          service.fullThread(u, t, f) || service.id(a, "uploaderId") == u.getUserId(),
          ErrorCode.FORBIDDEN,
          "无权访问其他接收人的协作附件");
    }
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(service.str(a, "filename"), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(service.id(a, "size"))
        .body(new InputStreamResource(storage.get(service.str(a, "storageKey"))));
  }
}
