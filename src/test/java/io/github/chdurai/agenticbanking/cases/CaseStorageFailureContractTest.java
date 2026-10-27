package io.github.chdurai.agenticbanking.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.controller.CaseController;
import io.github.chdurai.agenticbanking.controller.CaseProblemHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CaseStorageFailureContractTest {
    @Test void unavailableStorageReturns503WithoutAcceptingOrFallingBack() throws Exception {
        var cases = mock(AsyncCaseService.class);
        var store = mock(CaseStore.class);
        when(cases.submit(eq("document"), eq("idempotency-key"), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var mvc = MockMvcBuilders.standaloneSetup(new CaseController(cases, store, new ObjectMapper()))
                .setControllerAdvice(new CaseProblemHandler()).build();
        mvc.perform(post("/api/v1/document/cases").header("Idempotency-Key", "idempotency-key")
                        .contentType("application/json").content("{\"documentId\":\"one\",\"content\":\"invoice\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:agentic-banking:problem:storage-unavailable"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(store);
    }
}
