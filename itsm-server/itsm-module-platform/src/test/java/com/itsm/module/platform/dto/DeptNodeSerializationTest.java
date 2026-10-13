package com.itsm.module.platform.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeptNodeSerializationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesSnowflakeIdsAsStringsForBrowserClients() throws Exception {
        long id = 2109436731911725057L;
        long parentId = 2109438039787503617L;
        var json = mapper.readTree(mapper.writeValueAsString(
                DeptNode.of(id, parentId, "测试部门", 0, null, null, null, 1)));
        assertTrue(json.get("id").isTextual());
        assertEquals(Long.toString(id), json.get("id").asText());
        assertEquals(Long.toString(parentId), json.get("parentId").asText());
        var cmd = mapper.readValue("{\"parentId\":\"" + parentId
                + "\",\"name\":\"测试部门\"}", DeptSaveCmd.class);
        assertEquals(parentId, cmd.parentId());
    }

    @Test
    void userDepartmentIdUsesSameStringRepresentation() throws Exception {
        var user = new UserVO(1L, "user", "EMP0001", "员工", 2109436731911725057L,
                "测试部门", null, null, 1, java.util.List.of(), java.util.List.of(), null);
        var json = mapper.readTree(mapper.writeValueAsString(user));
        assertTrue(json.get("deptId").isTextual());
        assertEquals("2109436731911725057", json.get("deptId").asText());
    }
}
