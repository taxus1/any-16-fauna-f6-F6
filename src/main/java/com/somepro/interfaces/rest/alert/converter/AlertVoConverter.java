package com.somepro.interfaces.rest.alert.converter;

import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.interfaces.rest.alert.vo.AlertVO;
import com.somepro.interfaces.rest.common.vo.PageVO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * EpiAlert（领域）→ AlertVO（对外）转换器（用户接口层）。
 */
public final class AlertVoConverter {

    private AlertVoConverter() {
    }

    public static AlertVO toVo(EpiAlert alert) {
        return new AlertVO(alert.getId(), alert.getAlertNo(), alert.getReportId(),
                alert.getSampleId(), alert.getAlertLevel(), alert.getStatus(),
                alert.getDisposalMethod(), alert.getRaisedAt(), alert.getResolvedAt(),
                alert.getCreateTime());
    }

    public static PageVO<AlertVO> toPageVo(PageResult<EpiAlert> page) {
        List<AlertVO> content = page.content().stream()
                .map(AlertVoConverter::toVo)
                .collect(Collectors.toList());
        return new PageVO<>(content, page.total(), page.pageNum(), page.pageSize(), page.totalPages());
    }
}
