package io.github.chdurai.agenticbanking.documents;

import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.controller.DocumentUploadController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DocumentUploadConfigurationTest {
    @Test void binaryParsingEndpointRequiresAnExplicitOptIn() {
        var runner = new ApplicationContextRunner().withUserConfiguration(DocumentUploadController.class)
                .withBean(io.github.chdurai.agenticbanking.llm.LlmProperties.class, io.github.chdurai.agenticbanking.llm.LlmProperties::defaults)
                .withBean(DocumentService.class, () -> mock(DocumentService.class))
                .withBean(CaseService.class, () -> mock(CaseService.class));
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DocumentUploadController.class);
        });
        runner.withPropertyValues("platform.documents.pdf.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DocumentUploadController.class);
        });
    }
}
