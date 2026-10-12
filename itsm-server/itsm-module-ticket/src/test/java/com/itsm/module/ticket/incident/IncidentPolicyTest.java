package com.itsm.module.ticket.incident;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsm.common.api.ErrorCode;
import com.itsm.common.exception.BusinessException;
import com.itsm.module.platform.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IncidentPolicyTest {
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final IncidentService service =
      new IncidentService(db, new ObjectMapper(), mock(LoginUserLoader.class));

  @Test
  void adminCannotBypassSeatRequirement() {
    LoginUser u = LoginUser.builder().roles(Set.of("ADMIN")).perms(Set.of()).build();
    assertEquals(
        ErrorCode.FORBIDDEN,
        assertThrows(BusinessException.class, () -> service.assignPermission(u)).getErrorCode());
    verifyNoInteractions(db);
  }

  @Test
  void assignPermissionAloneIsInsufficient() {
    LoginUser u =
        LoginUser.builder().roles(Set.of("ENGINEER")).perms(Set.of("itsm:incident:assign")).build();
    assertThrows(BusinessException.class, () -> service.assignPermission(u));
    verifyNoInteractions(db);
  }

  @Test
  void priorityUsesProjectMatrix() {
    String[][] expected = {{"P1", "P2", "P3"}, {"P2", "P3", "P4"}, {"P3", "P4", "P4"}};
    String[] impact = {"WIDE", "PARTIAL", "SINGLE"}, urgency = {"HIGH", "MEDIUM", "LOW"};
    for (int i = 0; i < 3; i++)
      for (int j = 0; j < 3; j++)
        assertEquals(
            expected[i][j], service.priority(Map.of("impact", impact[i], "urgency", urgency[j])));
  }

  @Test
  void malformedIdentifiersHaveBusinessError() {
    assertEquals(
        ErrorCode.BAD_REQUEST,
        assertThrows(BusinessException.class, () -> service.id(Map.of("id", "1.5"), "id"))
            .getErrorCode());
  }

  @Test
  void futureOccurrenceIsRejected() {
    assertEquals(
        ErrorCode.BAD_REQUEST,
        assertThrows(
                BusinessException.class,
                () ->
                    service.occurred(
                        Map.of("occurredAt", java.time.Instant.now().plusSeconds(60).toString())))
            .getErrorCode());
  }

  @Test
  void stateGuardRejectsArchivedChanges() {
    assertEquals(
        ErrorCode.TICKET_STATE_ILLEGAL,
        assertThrows(
                BusinessException.class,
                () -> service.state(Map.of("status", "ARCHIVED"), "PROCESSING"))
            .getErrorCode());
  }
}
