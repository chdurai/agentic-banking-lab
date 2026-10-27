package io.github.chdurai.agenticbanking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import io.github.chdurai.agenticbanking.ops.AgentActivityHub;
import io.github.chdurai.agenticbanking.ops.ProviderExchangeContext;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
public class NvidiaChatConfig {

    @Bean
    RestClientCustomizer nvidiaStructuredOutputCustomizer(ObjectMapper mapper,
            ObjectProvider<AgentActivityHub> activity,
            @Value("${agents.nvidia.thinking-enabled:false}") boolean thinkingEnabled,
            @Value("${agents.nvidia.reasoning-budget:512}") int reasoningBudget) {
        if (reasoningBudget < 1) {
            throw new IllegalArgumentException("agents.nvidia.reasoning-budget must be positive");
        }
        // Spring AI 1.0 has no extra-body option for NVIDIA's chat-template settings.
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            if (request.getMethod() != HttpMethod.POST
                    || !"integrate.api.nvidia.com".equalsIgnoreCase(request.getURI().getHost())
                    || !"/v1/chat/completions".equals(request.getURI().getPath())) {
                return execution.execute(request, body);
            }
            var payload = mapper.readTree(body);
            if (payload instanceof ObjectNode object
                    && "nvidia/nemotron-3.5-lightning-30b-a3b".equals(object.path("model").asText())) {
                // These agents need final JSON, not reasoning that can exhaust the output allowance.
                object.withObject("/chat_template_kwargs").put("enable_thinking", thinkingEnabled);
                if (thinkingEnabled) {
                    int outputLimit = object.path("max_tokens").asInt(2048);
                    if (reasoningBudget >= outputLimit) {
                        throw new IllegalArgumentException("NVIDIA reasoning budget must be below max_tokens");
                    }
                    object.put("reasoning_budget", reasoningBudget);
                }
                body = mapper.writeValueAsBytes(object);
                request.getHeaders().setContentLength(body.length);
            }
            long started = System.nanoTime();
            ClientHttpResponse response = execution.execute(request, body);
            ProviderExchangeContext context = ProviderExchangeContext.current();
            AgentActivityHub hub = activity.getIfAvailable();
            if (context == null || hub == null) {
                return response;
            }
            // Buffer once so observation does not consume the body Spring AI must parse.
            byte[] bytes;
            try {
                bytes = response.getBody().readAllBytes();
            } catch (java.io.IOException e) {
                response.close();
                throw e;
            }
            hub.providerExchange(context, payload.path("model").asText(),
                    new String(body, StandardCharsets.UTF_8), new String(bytes, StandardCharsets.UTF_8),
                    response.getStatusCode().value(), (System.nanoTime() - started) / 1_000_000);
            return new ClientHttpResponse() {
                public HttpStatusCode getStatusCode() throws java.io.IOException { return response.getStatusCode(); }
                public String getStatusText() throws java.io.IOException { return response.getStatusText(); }
                public HttpHeaders getHeaders() { return response.getHeaders(); }
                public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                public void close() { response.close(); }
            };
        });
    }
}
