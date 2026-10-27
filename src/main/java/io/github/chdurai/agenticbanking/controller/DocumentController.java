package io.github.chdurai.agenticbanking.controller;

import jakarta.validation.Valid;
import io.github.chdurai.agenticbanking.documents.DocumentRequest;
import io.github.chdurai.agenticbanking.documents.DocumentResult;
import io.github.chdurai.agenticbanking.documents.DocumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;
    private final io.github.chdurai.agenticbanking.cases.CaseService cases;

    public DocumentController(DocumentService service, io.github.chdurai.agenticbanking.cases.CaseService cases) {
        this.service = service;
        this.cases = cases;
    }

    @PostMapping("/analyze")
    public DocumentResult analyze(@Valid @RequestBody DocumentRequest request,
                                  @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return cases.runIdempotent("document", key == null || key.isBlank() ? java.util.UUID.randomUUID().toString() : key,
                request.documentId(), request, DocumentResult.class, "document.case.completed", () -> service.analyze(request)).result();
    }

    @GetMapping("/history")
    public List<DocumentResult> history(@RequestParam(defaultValue = "20") int limit) {
        return service.recent(limit);
    }

    @GetMapping("/sample")
    public DocumentRequest sample() {
        return new DocumentRequest("DOC-3001", "Equipment invoice",
                "INVOICE #8841\nVendor: Northwind Supplies\nBill to: Acme Roads Ltd\nDate: 2025-03-14\n"
                        + "Total due: USD 48,200.00 within 30 days\nPO reference: PO-7710");
    }
}
