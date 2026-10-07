package com.somepro.interfaces.rest.alert.vo;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

/**
 * 预警处置请求（VO，用户接口层）。
 *
 * 已发布→处置中（处置中可继续补记措施），处置措施不能为空；
 * 已解除、已归档的不再收处置。
 */
public record AlertHandleRequest(
        @NotBlank(message = "处置措施不能为空") String disposalMethod) implements Serializable {
}
