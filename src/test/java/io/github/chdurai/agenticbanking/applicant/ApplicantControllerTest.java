package io.github.chdurai.agenticbanking.applicant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.*;
import io.github.chdurai.agenticbanking.controller.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApplicantControllerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AsyncCaseService cases = mock(AsyncCaseService.class);
    private final CaseController views = mock(CaseController.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ApplicantController(new DemoApplicantDirectory(), cases, views, json))
                .setControllerAdvice(new CaseProblemHandler()).build();
    }

    @Test void searchDoesNotExposeSsnAndOnboardingReturnsReusableResource() throws Exception {
        mvc.perform(get("/api/loans/applicants").param("query", "Jane"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].applicantId").value("A001"))
                .andExpect(jsonPath("$[0].ssnLast4").doesNotExist());
        String response = mvc.perform(post("/api/loans/applicants").contentType("application/json")
                        .content("""
                        {"fullName":"Taylor Demo","ssnLast4":"1234","annualIncome":85000,
                         "employmentYears":4,"employer":"Demo Works","existingMonthlyDebt":400}
                        """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.verificationStatus").value("DECLARED_NOT_VERIFIED"))
                .andExpect(jsonPath("$.ssnLast4").doesNotExist()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/loans/applicants/" + json.readTree(response).path("applicantId").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Taylor Demo"));
    }

    @Test void submissionUsesServerSideApplicantSnapshotAndExistingCaseContract() throws Exception {
        var now = Instant.now();
        var row = new CaseRecord("demo-case", "loan", "subject", "stable-key", "hash",
                CaseStatus.OPEN, 0, null, now, now);
        when(cases.submit(eq("loan"), eq("stable-key"), any())).thenAnswer(invocation -> {
            JsonNode payload = invocation.getArgument(2);
            assertThat(payload.path("applicantId").asText()).isEqualTo("A001");
            assertThat(payload.path("fullName").asText()).isEqualTo("Jane Cooper");
            assertThat(payload.path("annualIncome").asInt()).isEqualTo(145000);
            assertThat(payload.path("requestedAmount").asInt()).isEqualTo(25000);
            return new CaseStore.Claim(row, true);
        });
        when(views.view(row)).thenReturn(new CaseController.View("demo-case", "loan", "OPEN", 0, null, now, now));
        mvc.perform(post("/api/loans/applicants/A001/cases").header("Idempotency-Key", "stable-key")
                        .contentType("application/json")
                        .content("""
                        {"profileVersion":1,"requestedAmount":25000,"loanPurpose":"renovation",
                         "termMonths":60,"confirmed":true}
                        """))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/v1/cases/demo-case"))
                .andExpect(header().string("Retry-After", "1")).andExpect(jsonPath("$.caseId").value("demo-case"));
        verify(cases).submit(eq("loan"), eq("stable-key"), any());
    }

    @Test void invalidUnconfirmedAndStaleApplicationsDoNotSubmit() throws Exception {
        for (String body : new String[] {
                "{\"profileVersion\":1,\"requestedAmount\":500,\"loanPurpose\":\"repair\",\"termMonths\":60,\"confirmed\":true}",
                "{\"profileVersion\":1,\"requestedAmount\":25000,\"loanPurpose\":\"repair\",\"termMonths\":60,\"confirmed\":false}"
        }) {
            mvc.perform(post("/api/loans/applicants/A001/cases").header("Idempotency-Key", "key")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/loans/applicants/A001/cases").header("Idempotency-Key", "key")
                        .contentType("application/json")
                        .content("""
                        {"profileVersion":99,"requestedAmount":25000,"loanPurpose":"repair","termMonths":60,"confirmed":true}
                        """)).andExpect(status().isConflict());
        verifyNoInteractions(cases);
    }
}
