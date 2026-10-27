package io.github.chdurai.agenticbanking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NvidiaChatConfigTest {
    private static final String NVIDIA = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String LIGHTNING = "nvidia/nemotron-3.5-lightning-30b-a3b";

    @Test
    void rejectsReasoningBudgetThatLeavesNoFinalOutputAllowance() {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var builder = RestClient.builder();
        new NvidiaChatConfig().nvidiaStructuredOutputCustomizer(new ObjectMapper(),
                beans.getBeanProvider(io.github.chdurai.agenticbanking.ops.AgentActivityHub.class), true, 512).customize(builder);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build().post().uri(NVIDIA)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"model\":\"" + LIGHTNING + "\",\"max_tokens\":512}")
                .retrieve().toBodilessEntity())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoning budget must be below max_tokens");
    }

    @Test
    void capturesCorrelatedProviderResponseWithoutConsumingIt() {
        var hub = org.mockito.Mockito.mock(io.github.chdurai.agenticbanking.ops.AgentActivityHub.class);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("activity", hub);
        RestClient.Builder builder = RestClient.builder();
        new NvidiaChatConfig().nvidiaStructuredOutputCustomizer(new ObjectMapper(),
                beans.getBeanProvider(io.github.chdurai.agenticbanking.ops.AgentActivityHub.class), true, 512).customize(builder);
        var server = MockRestServiceServer.bindTo(builder).build();
        String input = "{\"model\":\"" + LIGHTNING + "\",\"max_tokens\":2048}";
        String output = """
                {"choices":[{"message":{"content":"answer","reasoning_content":"provider field"},
                 "finish_reason":"stop"}],"usage":{"completion_tokens":100}}
                """;
        server.expect(requestTo(NVIDIA))
                .andExpect(content().json("""
                        {"model":"%s","max_tokens":2048,"chat_template_kwargs":{"enable_thinking":true},
                         "reasoning_budget":512}
                        """.formatted(LIGHTNING), true))
                .andRespond(withSuccess(output, MediaType.APPLICATION_JSON));
        var context = new io.github.chdurai.agenticbanking.ops.ProviderExchangeContext("loan", "case1", "intake");
        io.github.chdurai.agenticbanking.ops.ProviderExchangeContext.set(context);
        try {
            org.assertj.core.api.Assertions.assertThat(builder.build().post().uri(NVIDIA)
                    .contentType(MediaType.APPLICATION_JSON).body(input).retrieve().body(String.class))
                    .isEqualTo(output);
            org.mockito.Mockito.verify(hub).providerExchange(
                    org.mockito.ArgumentMatchers.eq(context), org.mockito.ArgumentMatchers.eq(LIGHTNING),
                    org.mockito.ArgumentMatchers.contains("\"enable_thinking\":true"),
                    org.mockito.ArgumentMatchers.eq(output), org.mockito.ArgumentMatchers.eq(200),
                    org.mockito.ArgumentMatchers.anyLong());
        } finally {
            io.github.chdurai.agenticbanking.ops.ProviderExchangeContext.clear();
        }
        server.verify();
    }

    @Test
    void disablesThinkingWithoutChangingMessagesToolsOrTokenLimit() {
        String input = """
                {"model":"%s","messages":[{"role":"user","content":"Return JSON"}],
                 "max_tokens":2048,"tools":[]}
                """.formatted(LIGHTNING);
        String expected = """
                {"model":"%s","messages":[{"role":"user","content":"Return JSON"}],
                 "max_tokens":2048,"tools":[],"chat_template_kwargs":{"enable_thinking":false}}
                """.formatted(LIGHTNING);
        verifyRequest(NVIDIA, input, expected);
    }

    @Test
    void preservesOtherTemplateSettings() {
        verifyRequest(NVIDIA,
                """
                {"model":"%s","chat_template_kwargs":{"enable_thinking":true,"custom":"value"}}
                """.formatted(LIGHTNING),
                """
                {"model":"%s","chat_template_kwargs":{"enable_thinking":false,"custom":"value"}}
                """.formatted(LIGHTNING));
    }

    @Test
    void leavesOtherModelsUntouched() {
        String input = "{\"model\":\"another/model\",\"max_tokens\":2048}";
        verifyRequest(NVIDIA, input, input);
    }

    @Test
    void leavesOtherProvidersUntouched() {
        String input = "{\"model\":\"" + LIGHTNING + "\"}";
        verifyRequest("https://example.com/v1/chat/completions", input, input);
    }

    @Test
    void leavesEmbeddingsUntouched() {
        String input = "{\"model\":\"" + LIGHTNING + "\",\"input\":\"test\"}";
        verifyRequest("https://integrate.api.nvidia.com/v1/embeddings", input, input);
    }

    private void verifyRequest(String url, String input, String expected) {
        RestClient.Builder builder = RestClient.builder();
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        new NvidiaChatConfig().nvidiaStructuredOutputCustomizer(new ObjectMapper(),
                beans.getBeanProvider(io.github.chdurai.agenticbanking.ops.AgentActivityHub.class),
                false, 512).customize(builder);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url)).andExpect(method(HttpMethod.POST))
                .andExpect(content().json(expected, true))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        builder.build().post().uri(url).contentType(MediaType.APPLICATION_JSON)
                .body(input).retrieve().toBodilessEntity();
        server.verify();
    }
}
