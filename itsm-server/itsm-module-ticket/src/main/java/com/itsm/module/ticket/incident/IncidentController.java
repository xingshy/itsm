package com.itsm.module.ticket.incident;

import com.itsm.common.api.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {
  private final IncidentService service;

  @GetMapping
  public Result<?> list(@RequestParam Map<String, String> q) {
    return Result.ok(service.list(q));
  }

  @PostMapping
  public Result<?> create(
      @RequestBody Map<String, Object> b, @RequestHeader("Idempotency-Key") String k) {
    return Result.ok(service.create(b, k));
  }

  @GetMapping("/options")
  public Result<?> options() {
    return Result.ok(service.options());
  }

  @GetMapping("/recipient-options")
  public Result<?> recipients(@RequestParam Map<String, String> q) {
    return Result.ok(service.recipients(q));
  }

  @GetMapping("/notification-deliveries")
  public Result<?> deliveries(@RequestParam Map<String, String> q) {
    return Result.ok(service.deliveries(q));
  }

  @GetMapping("/counters")
  public Result<?> counters() {
    return Result.ok(service.counters());
  }

  @GetMapping("/notifications")
  public Result<?> notifications(@RequestParam Map<String, String> q) {
    return Result.ok(service.notifications(q));
  }

  @PostMapping("/notifications/{id}/read")
  public Result<?> readNotification(@PathVariable long id) {
    service.readNotification(id);
    return Result.ok(null);
  }

  @GetMapping("/{id}")
  public Result<?> detail(@PathVariable long id) {
    return Result.ok(service.detail(id));
  }

  @PutMapping("/{id}")
  public Result<?> edit(
      @PathVariable long id,
      @RequestBody Map<String, Object> b,
      @RequestHeader("Idempotency-Key") String k) {
    return Result.ok(service.mutate(id, "edit", null, b, k));
  }

  @DeleteMapping("/{id}/draft")
  public Result<?> deleteDraft(
      @PathVariable long id,
      @RequestBody Map<String, Object> b,
      @RequestHeader("Idempotency-Key") String k) {
    return Result.ok(service.mutate(id, "delete-draft", null, b, k));
  }

  @GetMapping("/{id}/{kind:activities|responsibilities|assignments|comments|cycles}")
  public Result<?> records(
      @PathVariable long id, @PathVariable String kind, @RequestParam Map<String, String> q) {
    return Result.ok(service.records(id, kind, q));
  }

  @PostMapping(
      "/{id}/{operation:submit|suspend|resume|resolve|close|archive|reopen|reopen-request|reject-resolution|remind|comments|assignments|transfers|forwards}")
  public Result<?> action(
      @PathVariable long id,
      @PathVariable String operation,
      @RequestBody Map<String, Object> b,
      @RequestHeader("Idempotency-Key") String k) {
    String op =
        switch (operation) {
          case "comments" -> "comment";
          case "assignments" -> "assign";
          case "transfers" -> "transfer";
          case "forwards" -> "forward";
          default -> operation;
        };
    return Result.ok(service.mutate(id, op, null, b, k));
  }

  @PostMapping(
      "/{id}/assignments/{taskId}/{operation:accept|withdraw|return-request|return-decision}")
  public Result<?> assignment(
      @PathVariable long id,
      @PathVariable long taskId,
      @PathVariable String operation,
      @RequestBody Map<String, Object> b,
      @RequestHeader("Idempotency-Key") String k) {
    return Result.ok(service.mutate(id, operation, taskId, b, k));
  }

  @GetMapping("/{id}/forwards")
  public Result<?> forwards(@PathVariable long id, @RequestParam Map<String, String> q) {
    return Result.ok(service.forwards(id, q));
  }

  @GetMapping("/{id}/forwards/{threadId}/replies")
  public Result<?> replies(
      @PathVariable long id, @PathVariable long threadId, @RequestParam Map<String, String> q) {
    return Result.ok(service.replies(id, threadId, q));
  }

  @PostMapping("/{id}/forwards/{threadId}/{operation:read|replies|withdraw}")
  public Result<?> thread(
      @PathVariable long id,
      @PathVariable long threadId,
      @PathVariable String operation,
      @RequestBody Map<String, Object> b,
      @RequestHeader("Idempotency-Key") String k) {
    return Result.ok(
        service.mutate(
            id,
            switch (operation) {
              case "replies" -> "reply";
              case "withdraw" -> "forward-withdraw";
              default -> operation;
            },
            threadId,
            b,
            k));
  }
}
