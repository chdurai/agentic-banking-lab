package io.github.chdurai.agenticbanking.showcase;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.chat.model.ChatModel;

@Configuration(proxyBeanMethods = false)
@Profile("showcase")
public class ShowcaseConfiguration {
    public ShowcaseConfiguration(org.springframework.core.env.Environment environment) {
        if (!isLocal(environment) || !"keyword".equals(environment.getProperty("agents.rag-mode", "keyword"))
                || environment.getProperty("spring.ai.mcp.client.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Showcase requires loopback, scripted provider, disabled remote models/MCP and keyword retrieval");
        }
    }

    public static boolean isLocal(org.springframework.core.env.Environment environment) {
        return environment.acceptsProfiles(org.springframework.core.env.Profiles.of("showcase"))
                && "127.0.0.1".equals(environment.getProperty("server.address"))
                && "none".equals(environment.getProperty("spring.ai.model.chat"))
                && "none".equals(environment.getProperty("spring.ai.model.embedding"))
                && "scripted-showcase".equals(environment.getProperty("platform.llm.provider"));
    }

    @Bean
    ChatModel showcaseChatModel() {
        return new ShowcaseChatModel();
    }
}
