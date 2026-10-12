package com.itsm.module.ticket.incident;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentNotificationDelivery {
  private final IncidentService service;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deliver(long id) {
    Map<String, Object> item =
        service.one("SELECT * FROM incident_notification_outbox WHERE id=? FOR UPDATE", id);
    if (!"PENDING".equals(item.get("status"))) return;
    service
        .db()
        .update(
            "INSERT INTO incident_notification(ticket_id,user_id,action_id,kind,message)"
                + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
            service.id(item, "ticketId"),
            service.id(item, "userId"),
            service.id(item, "actionId"),
            item.get("kind"),
            "事件有新进展");
    service
        .db()
        .update(
            "UPDATE incident_notification_outbox SET"
                + " status='DELIVERED',attempts=attempts+1,last_error=null,delivered_at=now() WHERE"
                + " id=?",
            id);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(long id) {
    service
        .db()
        .update(
            "UPDATE incident_notification_outbox SET"
                + " attempts=attempts+1,last_error='通知投递失败，系统将重试，请检查服务日志',next_attempt_at=now()+interval"
                + " '1 minute' WHERE id=? AND status='PENDING'",
            id);
  }
}
