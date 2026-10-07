package com.somepro.interfaces.rest.alert.vo;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 发布疫病预警请求（VO，用户接口层）。
 *
 * 只递阳性样本 id：只有检测结果为阳性的样本才立得了，阴性/待检/不确定一律拦下；
 * 预警级别由系统按来源观测保护级别快照与上报类别算，不由前端填；
 * 同一条阳性样本重复递进只留一条。raisedAt 可空（取发布当下）。
 */
public record AlertRaiseRequest(
        @NotNull(message = "阳性样本不能为空") Long sampleId,
        LocalDateTime raisedAt) implements Serializable {
}
