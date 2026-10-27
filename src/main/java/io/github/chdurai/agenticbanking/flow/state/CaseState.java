package io.github.chdurai.agenticbanking.flow.state;

import java.util.List;
import java.util.Optional;

/** Append-only blackboard for one case. Writers append; nobody mutates. */
public interface CaseState {

    String caseId();

    /** @throws IllegalArgumentException when provenance is missing or the finding belongs to another case. */
    Finding append(Finding finding);

    List<Finding> findings();

    Optional<Finding> latest(String agentId, Finding.Kind kind);

    /** Newest finding per agent, in first-appearance order. */
    List<Finding> currentView();

    /** Posts a message and returns it with the next sequence number. */
    AgentMessage post(AgentMessage message);

    List<AgentMessage> messages();

    boolean injectionSuspected();

    /** Independent copy used to give parallel agents the same frozen view. */
    CaseState copy();
}
