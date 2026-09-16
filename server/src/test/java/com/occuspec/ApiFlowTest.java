package com.occuspec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口联调冒烟测试：登录 → 人员 → 体检 → 判定 → 报告，全链路不断言外部模型。
 */
@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
class ApiFlowTest {
  @Autowired MockMvc mockMvc;

  @Test
  void 登录与鉴权() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"1009\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.token").exists());
  }

  @Test
  void 未登录访问受保护接口被拒绝() throws Exception {
    mockMvc.perform(get("/hazards"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  void 全链路_人员体检判定报告() throws Exception {
    // 登录取 token
    String loginResp = mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"1009\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn().getResponse().getContentAsString();
    String token = com.jayway.jsonpath.JsonPath.read(loginResp, "$.data.token");

    // 创建体检对象
    // 雪花 ID 超出 JS 安全整数，后端按安全范围序列化为字符串（见 SafeLongSerializer）
    String personResp = mockMvc.perform(post("/persons")
            .header("satoken", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"测试员\",\"gender\":\"男\",\"company\":\"测试厂\",\"jobType\":\"打磨\",\"exposureHistory\":\"噪声3年\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.personId").isString())
        .andReturn().getResponse().getContentAsString();
    String personId = com.jayway.jsonpath.JsonPath.read(personResp, "$.data.personId");

    // 录入体检记录（听力偏高，触发噪声规则）
    String examBody = "{\"personId\":\"" + personId + "\",\"hazardCode\":\"gbz188-7-1\",\"examDate\":\"2026-09-01\","
        + "\"items\":[{\"itemCode\":\"hearing_avg_db\",\"itemName\":\"双耳高频平均听阈\",\"valueNum\":45,\"unit\":\"dB\"}]}";
    String examResp = mockMvc.perform(post("/exams")
            .header("satoken", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(examBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.examId").isString())
        .andReturn().getResponse().getContentAsString();
    String examId = com.jayway.jsonpath.JsonPath.read(examResp, "$.data.examId");

    // 同步判定：规则应命中职业禁忌证
    String assessResp = mockMvc.perform(post("/assessments")
            .header("satoken", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"examId\":\"" + examId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.conclusion").value("OCCUPATIONAL_TABOO"))
        .andReturn().getResponse().getContentAsString();
    String assessmentId = com.jayway.jsonpath.JsonPath.read(assessResp, "$.data.assessmentId");

    // 报告查询：证据链非空且带复核声明
    mockMvc.perform(get("/assessments/" + assessmentId + "/report").header("satoken", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.evidences").isArray())
        .andExpect(jsonPath("$.data.disclaimer").exists());
  }
}
