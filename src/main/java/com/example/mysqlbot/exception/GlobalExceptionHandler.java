package com.example.mysqlbot.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolationException(
            DataIntegrityViolationException e) {
        String message = "数据完整性冲突，可能是数据重复或不符合约束条件。";
        // Check for specific duplicate key message
        if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")
                && e.getMessage().contains("uk_name")) {
            message = "已经存在相同名称的数据源，请更换名称。";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", message, "error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "服务器内部错误", "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
    }
}
