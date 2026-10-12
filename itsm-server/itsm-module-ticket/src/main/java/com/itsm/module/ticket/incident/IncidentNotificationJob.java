package com.itsm.module.ticket.incident;

import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IncidentNotificationJob {
  private final IncidentService service;
  private final IncidentNotificationDelivery delivery;

  @Scheduled(fixedDelayString = "${itsm.incident.notification-interval-ms:5000}")
  public void deliverPending() {
    for (Map<String, Object> item :
        service.rows(
            "SELECT id FROM incident_notification_outbox WHERE status='PENDING' AND"
                + " next_attempt_at<=now() ORDER BY id LIMIT 100")) {
      long id = service.id(item, "id");
      try {
        delivery.deliver(id);
      } catch (Exception e) {
        log.error("事件通知投递失败 outboxId={}", id, e);
        delivery.failed(id);
      }
    }
  }
}
