package io.github.chdurai.agenticbanking.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.chdurai.agenticbanking.cases.CaseService;

/** Content identity, not a digital signature or proof of source authenticity. */
public record ApprovalEvidenceSeal(String snapshotJson, String sha256) {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public static ApprovalEvidenceSeal of(ApprovalRequest request) {
        var snapshot = JSON.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("id", request.id());
        snapshot.put("flow", request.flow());
        snapshot.put("subjectId", request.subjectId());
        snapshot.put("title", request.title());
        snapshot.set("details", JSON.valueToTree(request.details()));
        snapshot.set("evidence", JSON.valueToTree(request.evidencePacket()));
        snapshot.set("action", JSON.valueToTree(request.action()));
        snapshot.put("requestedBy", request.requestedBy());
        snapshot.put("requiredRole", request.requiredRole());
        snapshot.set("createdAt", JSON.valueToTree(request.createdAt()));
        snapshot.set("expiresAt", JSON.valueToTree(request.expiresAt()));
        snapshot.put("blindFirstRequired", request.blindFirstRequired());
        snapshot.put("reasonCodeRequired", request.reasonCodeRequired());
        try {
            String json = JSON.writeValueAsString(snapshot);
            return new ApprovalEvidenceSeal(json, CaseService.sha256(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot freeze approval evidence", e);
        }
    }
}
