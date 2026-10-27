package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.mcp.PlatformMcpTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(AgentFlowsStubbedTest.StubConfig.class)
class PlatformMcpToolsTest {

    @Autowired PlatformMcpTools tools;
    @Autowired List<ToolCallbackProvider> providers;

    @Test
    void exposesTheReadOnlyTools() {
        List<String> names = providers.stream().flatMap(p -> Arrays.stream(p.getToolCallbacks()))
                .map(ToolCallback::getToolDefinition).map(d -> d.name()).toList();
        assertThat(names).contains("searchKnowledge", "getServiceGraph", "getCostReport",
                "getPendingApprovalCounts");
    }

    @Test
    void searchAndGraphToolsReturnData() {
        assertThat(tools.searchKnowledge("connection pool exhausted", 2).get("runbooks")).isNotEmpty();
        assertThat(tools.searchKnowledge("unpaid wages", null).get("grievancePlaybooks")).isNotEmpty();
        assertThat(tools.getServiceGraph("loan-db")).doesNotContainKey("error");
        assertThat(tools.getServiceGraph("nope")).containsKey("error");
    }
}
