package com.stockcat.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参数校验类异常必须映射成 400，不能落到兜底的 500（P1.3）。
 *
 * <p>起因：{@code GET /instruments/1/indicators?limit=9999} 原本返回
 * <b>500 INTERNAL_ERROR</b>。原因是 {@code @Max} 写在 {@code @RequestParam} 上，
 * Spring 抛的是 {@code ConstraintViolationException}，而全局异常处理只接了
 * {@code MethodArgumentNotValidException}（那是 {@code @Valid} 请求体用的），
 * 于是被 {@code Exception} 兜底成"服务器内部错误"。
 *
 * <p>为什么不是 MockMvc 测试：{@code @WebMvcTest} 会触发应用类上的 {@code @MapperScan}，
 * 需要 SqlSessionFactory 才能建 Mapper Bean，等于为了测三个参数校验去起数据库。
 * 这里直接对异常处理做单元测试，真实的 400 行为在联调时用 curl 验证。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/api/v1/instruments/1/indicators");

    @Test
    void constraintViolationBecomes400InsteadOf500() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<Probe>> violations = validator.validate(new Probe(9999));
        assertThat(violations).isNotEmpty();

        ResponseEntity<ApiError> response =
                handler.handleConstraintViolation(new ConstraintViolationException(violations), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.details()).isNotEmpty();
        assertThat(body.path()).isEqualTo("/api/v1/instruments/1/indicators");
    }

    @Test
    void typeMismatchBecomes400() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "limit", null, new NumberFormatException("abc"));

        ResponseEntity<ApiError> response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().details()).anyMatch(d -> d.contains("limit"));
    }

    @Test
    void apiExceptionKeepsItsOwnStatusAndCode() {
        ResponseEntity<ApiError> response =
                handler.handleApiException(ApiException.notFound("标的不存在: id=999"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    /** 只为构造一次真实的校验失败，参数名故意叫 limit 以贴近实际场景。 */
    private record Probe(@Max(500) int limit) {
    }
}
