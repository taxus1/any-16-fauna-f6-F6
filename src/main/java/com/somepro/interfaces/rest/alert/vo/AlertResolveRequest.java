package com.somepro.interfaces.rest.alert.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 预警解除请求（VO，用户接口层）。
 *
 * 处置中→已解除，还没处置的不能直接解除，已解除的不能重复解除；
 * resolvedAt 可空（取解除当下）。
 */
public record AlertResolveRequest(LocalDateTime resolvedAt) implements Serializable {
}
