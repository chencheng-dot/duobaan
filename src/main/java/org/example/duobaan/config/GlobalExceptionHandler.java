package org.example.duobaan.config;

import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一错误响应。
 * 约定：
 *  - 4xx 记 warn、5xx 记 error：此前服务端没有任何异常日志，线上故障零线索；
 *  - 对外只给可读文案，不把底层异常 message 或厂商原始报文直出前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        // 不再强转 (HttpStatus)：响应码可能来自 HttpStatusCode.valueOf(...)，强转会 ClassCastException
        HttpStatusCode code = ex.getStatusCode();
        String reason = ex.getReason() == null ? "请求失败" : ex.getReason();
        if (code.is5xxServerError()) {
            log.error("业务异常 status={} reason={}", code.value(), ex.getReason(), ex);
        } else {
            log.warn("业务异常 status={} reason={}", code.value(), ex.getReason());
        }
        return ResponseEntity.status(code).body(Map.of("status", code.value(), "message", reason));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegal(IllegalArgumentException ex) {
        log.warn("参数错误：{}", ex.getMessage());
        // 第 4 批脱敏：IllegalArgumentException 覆盖面很广（含 OutboundUrlGuard 守卫拒绝、MediaService 参数校验），
        // 原文可能带上 URL/主机名等细节 —— 一律只回固定文案；原文已由上面的 log.warn 落日志。
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "message", "请求参数不合法，请检查后重试。"));
    }

    /** Bean Validation（@Valid）失败 → 400，取第一条字段错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : e.getField() + " 参数非法")
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验失败：{}", msg);
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", msg));
    }

    /** 上传超限：multipart 上限见 spring.servlet.multipart.max-*，超限原先直接变成 500 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("上传文件过大：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "status", 413,
                "message", "上传文件过大，请压缩后重试（音频 ≤25MB）"));
    }

    /**
     * 请求体不是合法 JSON —— 必须显式处理。
     * HttpMessageNotReadableException **不实现** ErrorResponse，若不单独声明，
     * 它会被下面的 handleAny 当成未知异常返回 500（实测：发一个坏 JSON 得到 500 而不是 400）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败：{}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", "请求体不是合法的 JSON"));
    }

    /** 路径/查询参数类型不匹配（如 ?group=XXX 传了非法枚举值）→ 400，同理不能落进兜底变 500 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("参数类型不匹配：{} = {}", ex.getName(), ex.getValue());
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "message", "参数 " + ex.getName() + " 取值非法"));
    }

    /** 方法参数上的 @Validated 约束失败 → 400 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .map(v -> v.getMessage() != null ? v.getMessage() : String.valueOf(v.getPropertyPath()))
                .findFirst().orElse("参数校验失败");
        log.warn("参数约束失败：{}", msg);
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", msg));
    }

    /**
     * 瞬时并发锁冲突（InnoDB 1213 死锁 / 1205 锁等待超时）→ 503 而不是 500。
     * 这类失败是「稍后重试即可成功」的瞬时状态，500 会让调用方以为服务真坏了；
     * 且 ChatAppendRetry 已在事务边界外重试过一次，能走到这里说明重试仍未成功。
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleLockConflict(PessimisticLockingFailureException ex) {
        log.warn("并发锁冲突：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", 503,
                "message", "并发写入冲突，请重试。"));
    }

    /**
     * 兜底：未捕获异常统一 500 JSON，并且必须留下堆栈日志。
     * Spring MVC 自身的 4xx 异常（405 方法不支持、415 媒体类型不支持、400 缺少参数等）大多实现了 ErrorResponse，
     * 必须原样透传它们的状态码，否则会被错误地变成 500。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            HttpStatusCode code = er.getStatusCode();
            String detail = null;
            try {
                detail = er.getBody().getDetail();
            } catch (Exception ignore) {
                // 个别实现没有 detail，忽略即可
            }
            log.warn("请求异常 status={} type={} msg={}", code.value(), ex.getClass().getSimpleName(), ex.getMessage());
            // 4xx 直接给出具体原因（如「缺少请求参数 type」），对调用方可读且不含敏感信息
            String msg = (code.is4xxClientError() && detail != null && !detail.isBlank())
                    ? detail
                    : "请求处理失败";
            return ResponseEntity.status(code).body(Map.of("status", code.value(), "message", msg));
        }
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", 500,
                "message", "服务器内部错误，请查看后端日志"));
    }
}
