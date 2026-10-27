package io.github.chdurai.agenticbanking.ops;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Emits platform events. Producers depend on this, never on a transport. */
@Component
public class PlatformEventPublisher {

    private final ApplicationEventPublisher delegate;

    public PlatformEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    public void publish(PlatformEvent event) {
        delegate.publishEvent(event);
    }
}
