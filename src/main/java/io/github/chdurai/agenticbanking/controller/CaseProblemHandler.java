package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.cases.CaseStoreExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@RestControllerAdvice(assignableTypes = {CaseController.class, FlowBridgeController.class, ApprovalDecisionController.class,
        DocumentController.class, GrievanceController.class, IncidentController.class, CaseLinkController.class,
        GrievanceInsightsController.class, DocumentUploadController.class, ApplicantController.class,
        InvestigationController.class, io.github.chdurai.agenticbanking.showcase.ShowcaseController.class})
public class CaseProblemHandler {
    private static final Logger log = LoggerFactory.getLogger(CaseProblemHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException e) {
        return problem(e.getStatusCode().value(), "request-rejected", e.getReason());
    }
    @ExceptionHandler({CaseStoreExceptions.IdempotencyConflictException.class, CaseStoreExceptions.StaleVersionException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException e) {
        return problem(409, "conflict", "Request conflicts with stored state");
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e) {
        return problem(400, "invalid-request", "Invalid request");
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> uploadTooLarge(Exception e) {
        return problem(413, "upload-too-large", "Upload exceeds the configured multipart limit");
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException e) {
        log.error("Durable case storage unavailable", e);
        return problem(503, "storage-unavailable", "Case storage is unavailable");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Case API failure", e);
        return problem(500, "internal-error", "Request could not be completed");
    }
    private ResponseEntity<ProblemDetail> problem(int status, String type, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        body.setType(URI.create("urn:agentic-banking:problem:" + type));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
