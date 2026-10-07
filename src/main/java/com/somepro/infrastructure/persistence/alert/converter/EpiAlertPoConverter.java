package com.somepro.infrastructure.persistence.alert.converter;

import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.infrastructure.persistence.alert.po.EpiAlertPO;

/**
 * EpiAlertPO（表）↔ EpiAlert（领域）转换器（基础设施层）。
 */
public final class EpiAlertPoConverter {

    private EpiAlertPoConverter() {
    }

    public static EpiAlertPO toPo(EpiAlert domain) {
        EpiAlertPO po = new EpiAlertPO();
        po.setId(domain.getId());
        po.setAlertNo(domain.getAlertNo());
        po.setReportId(domain.getReportId());
        po.setSampleId(domain.getSampleId());
        po.setAlertLevel(domain.getAlertLevel());
        po.setStatus(domain.getStatus());
        po.setDisposalMethod(domain.getDisposalMethod());
        po.setRaisedAt(domain.getRaisedAt());
        po.setResolvedAt(domain.getResolvedAt());
        po.setDelFlag(domain.getDelFlag());
        po.setCreateBy(domain.getCreateBy());
        po.setCreateTime(domain.getCreateTime());
        po.setUpdateBy(domain.getUpdateBy());
        po.setUpdateTime(domain.getUpdateTime());
        return po;
    }

    public static EpiAlert toDomain(EpiAlertPO po) {
        EpiAlert domain = new EpiAlert();
        domain.setId(po.getId());
        domain.setAlertNo(po.getAlertNo());
        domain.setReportId(po.getReportId());
        domain.setSampleId(po.getSampleId());
        domain.setAlertLevel(po.getAlertLevel());
        domain.setStatus(po.getStatus());
        domain.setDisposalMethod(po.getDisposalMethod());
        domain.setRaisedAt(po.getRaisedAt());
        domain.setResolvedAt(po.getResolvedAt());
        domain.setDelFlag(po.getDelFlag());
        domain.setCreateBy(po.getCreateBy());
        domain.setCreateTime(po.getCreateTime());
        domain.setUpdateBy(po.getUpdateBy());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }
}
