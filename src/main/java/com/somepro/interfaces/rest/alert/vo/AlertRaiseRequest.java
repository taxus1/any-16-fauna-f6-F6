package com.somepro.interfaces.rest.alert.vo;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 发布预警请求（VO，用户接口层）。
 *
 * 前置由应用层把关：样本在册且结果为阳性（阴性/待检/不确定立不了），挂的上报与观测在册；
 * 级别不用前端填，由系统按观测保护级别快照与上报类别算；同一条阳性样本只立一条。
 * raisedAt 可空（取发布当下）。
 */
public record AlertRaiseRequest(
        @NotNull(message = "阳性样本不能为空") Long sampleId,
        LocalDateTime raisedAt) implements Serializable {
}
