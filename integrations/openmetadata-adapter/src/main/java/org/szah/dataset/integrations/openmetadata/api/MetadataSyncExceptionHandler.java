package org.szah.dataset.integrations.openmetadata.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.szah.dataset.integrations.openmetadata.sync.MetadataMappingConflictException;
import org.szah.dataset.integrations.openmetadata.sync.MetadataSyncException;

@RestControllerAdvice
public class MetadataSyncExceptionHandler {
    @ExceptionHandler(MetadataMappingConflictException.class)
    ProblemDetail mappingConflict(MetadataMappingConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MetadataSyncException.class)
    ProblemDetail syncFailure(MetadataSyncException exception) {
        return problem(HttpStatus.BAD_GATEWAY, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request validation failed");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code);
        problem.setProperty("code", code);
        return problem;
    }
}
