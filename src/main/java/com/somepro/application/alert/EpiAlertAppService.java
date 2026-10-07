package com.somepro.application.alert;

import com.somepro.common.exception.BizException;
import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.alert.repository.EpiAlertRepository;
import com.somepro.domain.obs.model.WildlifeObs;
import com.somepro.domain.obs.repository.WildlifeObsRepository;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.report.repository.AbnormalReportRepository;
import com.somepro.domain.sample.model.SampleTest;
import com.somepro.domain.sample.repository.SampleTestRepository;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * 疫病预警应用层：编排预警用例（发布、处置、解除、查看、条件分页）。
 *
 * 发布前置（缺一不可）：
 * - 样本得在册、且结果是阳性 —— 阴性、待检、不确定的都立不了；
 * - 样本挂的上报、上报挂的观测都得在册（作废/销账的查不到，一并拦下）；
 *   算级别用的保护级别只认观测上抄下来的那份快照，不读物种名录现在改成的样子。
 * 「同一条阳性样本只立一条预警」由仓储层在事务内锁住样本行兜底：两人前后脚一起递只落一条。
 *
 * 级别怎么算是领域规则（{@link EpiAlert#raise}），状态机也由领域对象把守
 * （已发布→处置中→已解除，不跳级不回退），仓储条件更新兜并发。
 */
@Service
public class EpiAlertAppService {

    private final EpiAlertRepository alertRepository;
    private final SampleTestRepository sampleRepository;
    private final AbnormalReportRepository reportRepository;
    private final WildlifeObsRepository obsRepository;

    public EpiAlertAppService(EpiAlertRepository alertRepository,
                              SampleTestRepository sampleRepository,
                              AbnormalReportRepository reportRepository,
                              WildlifeObsRepository obsRepository) {
        this.alertRepository = alertRepository;
        this.sampleRepository = sampleRepository;
        this.reportRepository = reportRepository;
        this.obsRepository = obsRepository;
    }

    /**
     * 发布一条预警：编号 AL-YYYY-NNNN 由仓储层生成；级别由系统按这单的来头算
     * （保护级别快照定起评档，死亡/疑似疫病抬一档，受伤不抬）；发布时刻不传取当下；
     * 立起来落在已发布。只有阳性样本立得了，同一条阳性样本重复递进只留一条。
     */
    public Mono<EpiAlert> raise(Long sampleId, LocalDateTime raisedAt) {
        return requirePositiveSample(sampleId)
                .flatMap(sample -> reportRepository.findById(sample.getReportId())
                        .switchIfEmpty(Mono.error(new BizException("样本挂的上报不存在或已作废，不能发布预警")))
                        .flatMap(report -> requireRaisableObs(report)
                                .flatMap(obs -> {
                                    EpiAlert alert = EpiAlert.raise(report.getId(), sample.getId(),
                                            obs.getProtectionLevel(), report.getCategory(), raisedAt);
                                    return alertRepository.create(alert);
                                })));
    }

    /**
     * 处置：已发布→处置中并记下处置措施（处置中也可再补措施）；已解除的动不了；
     * 并发处置同一单由条件更新兜底，只有一下翻得动。
     */
    public Mono<EpiAlert> handle(Long id, String disposalMethod) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("预警不存在")))
                .flatMap(alert -> {
                    String fromStatus = alert.getStatus();
                    alert.handle(disposalMethod);
                    return alertRepository.handle(alert, fromStatus)
                            .flatMap(flipped -> flipped
                                    // 重查一遍：处置刷新了审计列 update_time，内存里的还是处置前的
                                    ? alertRepository.findById(alert.getId())
                                    : Mono.error(new BizException("预警状态已变化，请刷新后重试")));
                });
    }

    /**
     * 解除：处置中→已解除，解除时刻落下（不传取当下）；还没处置的不能直接解除，
     * 已解除的不能重复解除；并发解除同一单由条件更新兜底，只有一下翻得动。
     */
    public Mono<EpiAlert> resolve(Long id, LocalDateTime resolvedAt) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("预警不存在")))
                .flatMap(alert -> {
                    String fromStatus = alert.getStatus();
                    alert.resolve(resolvedAt);
                    return alertRepository.resolve(alert, fromStatus)
                            .flatMap(flipped -> flipped
                                    ? alertRepository.findById(alert.getId())
                                    : Mono.error(new BizException("预警状态已变化，请刷新后重试")));
                });
    }

    /** 查看单条在册预警。 */
    public Mono<EpiAlert> detail(Long id) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("预警不存在")));
    }

    /** 条件分页：上报/样本/级别/状态随意拼，全空翻整份在册预警，每行带预警编号。 */
    public Mono<PageResult<EpiAlert>> pageAlerts(int pageNum, int pageSize,
                                                 Long reportId, Long sampleId,
                                                 String alertLevel, String status) {
        return alertRepository.page(pageNum, pageSize, reportId, sampleId,
                normalize(alertLevel), normalize(status));
    }

    /** 样本得在册、且结果是阳性：阴性、待检、不确定的都立不了预警。 */
    private Mono<SampleTest> requirePositiveSample(Long sampleId) {
        if (sampleId == null) {
            return Mono.error(new BizException("阳性样本不能为空"));
        }
        return sampleRepository.findById(sampleId)
                .switchIfEmpty(Mono.error(new BizException("样本不存在，不能发布预警")))
                .flatMap(sample -> {
                    if (!SampleTest.RESULT_POSITIVE.equals(sample.getResult())) {
                        return Mono.error(new BizException("只有检出阳性的样本才能发布预警，阴性/待检/不确定的样本立不了"));
                    }
                    return Mono.just(sample);
                });
    }

    /** 上报挂的观测得在册：算级别要读它上面的保护级别快照，作废观测翻不到，一并拦下。 */
    private Mono<WildlifeObs> requireRaisableObs(AbnormalReport report) {
        return obsRepository.findById(report.getObsId())
                .switchIfEmpty(Mono.error(new BizException("上报挂的观测不存在或已作废，不能发布预警")));
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
