package com.itsm.module.ticket.incident;

import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class IncidentReminderJob {
  private final IncidentService service;

  @Scheduled(fixedDelayString = "${itsm.incident.reminder-interval-ms:60000}")
  @Transactional
  public void remind() {
    // Query only overdue candidates; completed reminder keys never starve later events.
    List<Map<String, Object>> tickets =
        service.rows(
            "SELECT t.* FROM ticket t WHERE t.type='INCIDENT' AND t.draft_deleted=false AND"
                + " t.status IN ('PENDING_ASSIGNMENT','PENDING_TAKEOVER','PROCESSING','SUSPENDED')"
                + " AND ((t.first_response_at IS NULL AND t.response_due_at<now() AND NOT"
                + " EXISTS(SELECT 1 FROM incident_reminder r WHERE r.ticket_id=t.id AND"
                + " r.kind='RESPONSE_OVERDUE')) OR ((t.status<>'SUSPENDED' OR"
                + " t.suspend_pause=false) AND t.resolution_due_at<now() AND NOT EXISTS(SELECT 1"
                + " FROM incident_reminder r WHERE r.ticket_id=t.id AND r.kind='RESOLUTION_OVERDUE'"
                + " AND r.reference=CAST(t.cycle AS TEXT))) OR (t.status='SUSPENDED' AND"
                + " t.expected_resume_at<now() AND NOT EXISTS(SELECT 1 FROM incident_reminder r"
                + " WHERE r.ticket_id=t.id AND r.kind='RESUME_OVERDUE' AND"
                + " r.reference=CAST(CAST(floor(extract(epoch from t.suspended_at)*1000) AS BIGINT)"
                + " AS TEXT))) OR EXISTS(SELECT 1 FROM incident_assignment a WHERE a.ticket_id=t.id"
                + " AND a.status='PENDING' AND a.due_at<now() AND NOT EXISTS(SELECT 1 FROM"
                + " incident_reminder r WHERE r.ticket_id=t.id AND r.kind='TAKEOVER_OVERDUE' AND"
                + " r.reference=CAST(a.id AS TEXT)))) ORDER BY t.id LIMIT 100 FOR UPDATE SKIP"
                + " LOCKED");
    Instant now = Instant.now();
    for (Map<String, Object> t : tickets) {
      long ticket = service.id(t, "id");
      for (Map<String, Object> a :
          service.rows(
              "SELECT * FROM incident_assignment WHERE ticket_id=? AND status='PENDING' AND"
                  + " due_at<now()",
              ticket))
        send(t, "TAKEOVER_OVERDUE", service.str(a, "id"), service.id(a, "targetUserId"));
      if (t.get("firstResponseAt") == null && past(t, "responseDueAt", now))
        send(t, "RESPONSE_OVERDUE", "first", service.id(t, "pendingUserId"));
      if ((!"SUSPENDED".equals(t.get("status")) || Boolean.FALSE.equals(t.get("suspendPause")))
          && past(t, "resolutionDueAt", now))
        send(t, "RESOLUTION_OVERDUE", service.str(t, "cycle"), service.id(t, "ownerId"));
      if ("SUSPENDED".equals(t.get("status")) && past(t, "expectedResumeAt", now))
        send(
            t,
            "RESUME_OVERDUE",
            Long.toString(Instant.parse(service.str(t, "suspendedAt")).toEpochMilli()),
            service.id(t, "ownerId"));
    }
  }

  boolean past(Map<String, Object> t, String key, Instant now) {
    return t.get(key) != null && Instant.parse(service.str(t, key)).isBefore(now);
  }

  void send(Map<String, Object> t, String kind, String ref, long recipient) {
    long ticket = service.id(t, "id");
    if (service
            .db()
            .update(
                "INSERT INTO incident_reminder(ticket_id,kind,reference) VALUES(?,?,?) ON CONFLICT"
                    + " DO NOTHING",
                ticket,
                kind,
                ref)
        == 0) return;
    service.db().update("UPDATE ticket SET action_seq=action_seq+1 WHERE id=?", ticket);
    long a =
        service
            .db()
            .queryForObject(
                "INSERT INTO"
                    + " ticket_action(ticket_id,seq,action,actor_id,actor_name,before_status,after_status,details,cycle)"
                    + " SELECT id,action_seq,?,0,'系统',status,status,?,cycle FROM ticket WHERE id=?"
                    + " RETURNING id",
                Long.class,
                kind,
                service.encode(Map.of("reference", ref)),
                ticket);
    service.notify(ticket, a, recipient, kind);
    service.notifySeats(ticket, a, kind);
  }
}
