package com.payflow.gateway.handler;

import com.payflow.gateway.exception.IdempotencyInProgressException;
import com.payflow.gateway.exception.IdempotencyKeyConflictException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.payflow.gateway.exception.PaymentNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(PaymentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(message));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(exception.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(IdempotencyKeyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(exception.getMessage()));
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyInProgress(IdempotencyInProgressException exception) {
        // Retry-After підказує клієнту почекати, а не одразу бити повторним
        // запитом - той самий підхід, що планується для 503 на переповнену
        // чергу в стадії 3.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(errorBody(exception.getMessage()));
    }

    /**
     * Покриває вичерпання пулу з'єднань і подібні тимчасові проблеми з базою -
     * саме той сценарій, який стається під дуже високим конкурентним
     * навантаженням (наприклад, сотні одночасних запитів з одним ключем
     * ідемпотентності б'ються за ті самі кілька рядків). Чесний 503 з
     * Retry-After краще за випадковий 500 без пояснень.
     *
     * <p>Обробляємо і {@link DataAccessException}, і {@link TransactionException}
     * окремо - вичерпаний пул з'єднань кидає {@code CannotCreateTransactionException}
     * (гілка TransactionException, не DataAccessException), тож без другого
     * типу частина збоїв проходила б повз цей обробник непоміченою.
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Map<String, Object>> handleDataAccessFailure(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(errorBody("Тимчасові проблеми з базою даних, спробуйте пізніше"));
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("message", message);
        return body;
    }
}
