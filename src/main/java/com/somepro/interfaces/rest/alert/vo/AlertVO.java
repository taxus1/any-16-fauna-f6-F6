package com.somepro.interfaces.rest.alert.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 疫病预警对外返回对象（VO，用户接口层）—— 不可变 record。预警编号 alertNo 必带，
 * 挂在哪条上报、哪个阳性样本上都回出，级别/状态/处置措施/发布时刻/解除时刻一并带上。
 */
public record AlertVO(Long id, String alertNo, Long reportId, Long sampleId,
                      String alertLevel, String status, String disposalMethod,
                      LocalDateTime raisedAt, LocalDateTime resolvedAt,
                      LocalDateTime createTime) implements Serializable {
}
