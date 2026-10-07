package com.somepro.interfaces.rest.alert.vo;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

/**
 * 预警处置请求（VO，用户接口层）。
 *
 * 已发布→处置中并记下处置措施（处置中也可再补措施）；已解除的动不了。
 */
public record AlertHandleRequest(
        @NotBlank(message = "处置措施不能为空") String disposalMethod) implements Serializable {
}
