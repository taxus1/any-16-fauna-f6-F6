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
 * 疫病预警应用层：编排预警用例（发布、处置、解除、归档、查看、条件分页）。
 *
 * 发布前置（缺一不可）：
 * - 样本得在册，且检测结果是阳性 —— 阴性、待检、不确定都立不出预警；
 * - 样本挂的那条异常上报得在册（已作废的连带立不了）；
 * - 上报来源的那条观测得在册：预警级别起步档看的是观测上抄的保护级别快照，
 *   抬不抬档看上报类别（死亡/疑似疫病抬一档，受伤不抬），不去读物种名录现在改成的样子。
 * 另外「同一条阳性样本只立一条预警」由仓储层在事务内行锁兜底：重复递进只留一条。
 *
 * 级别由领域对象按来头算，不由前端填；编号 AL-YYYY-NNNN 由仓储层生成。
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
     * 发布一条预警：只有阳性样本立得了；级别系统按来源观测保护级别快照 + 上报类别算；
     * 发布时刻不传取发布当下；同一条阳性样本重复递进只留一条（仓储事务兜底）。
     */
    public Mono<EpiAlert> raise(Long sampleId, LocalDateTime raisedAt) {
        return requirePositiveSample(sampleId)
                .flatMap(sample -> reportRepository.findById(sample.getReportId())
                        .switchIfEmpty(Mono.error(new BizException("样本关联的异常上报不存在或已作废，不能发布预警")))
                        .flatMap(report -> requireSourceObs(report)
                                .map(obs -> EpiAlert.raise(report.getId(), sample.getId(),
                                        obs.getProtectionLevel(), report.getCategory(), raisedAt)))
                        .flatMap(alertRepository::create));
    }

    /**
     * 处置：已发布→处置中（处置中的可继续补记措施），记下处置措施；已解除/已归档不再收；
     * 并发处置同一单由条件更新兜底，只有一下翻得动。
     */
    public Mono<EpiAlert> handle(Long id, String disposalMethod) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("疫病预警不存在")))
                .flatMap(alert -> {
                    String fromStatus = alert.getStatus();
                    alert.handle(disposalMethod);
                    return alertRepository.handle(alert, fromStatus)
                            .flatMap(flipped -> flipped
                                    ? alertRepository.findById(alert.getId())
                                    : Mono.error(new BizException("预警状态已变化，请刷新后重试")));
                });
    }

    /** 解除：处置中→已解除，解除时刻落下来；没开处置、已解除、已归档都解不了。 */
    public Mono<EpiAlert> resolve(Long id, LocalDateTime resolvedAt) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("疫病预警不存在")))
                .flatMap(alert -> {
                    alert.resolve(resolvedAt);
                    return alertRepository.resolve(alert)
                            .flatMap(flipped -> flipped
                                    ? alertRepository.findById(alert.getId())
                                    : Mono.error(new BizException("预警状态已变化，请刷新后重试")));
                });
    }

    /** 归档：已解除→已归档，归档是终态。 */
    public Mono<EpiAlert> archive(Long id) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("疫病预警不存在")))
                .flatMap(alert -> {
                    alert.archive();
                    return alertRepository.archive(alert.getId())
                            .flatMap(flipped -> flipped
                                    ? alertRepository.findById(alert.getId())
                                    : Mono.error(new BizException("预警状态已变化，请刷新后重试")));
                });
    }

    /** 查看单条在册预警。 */
    public Mono<EpiAlert> detail(Long id) {
        return alertRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("疫病预警不存在")));
    }

    /** 条件分页：上报/样本/级别/状态随意拼，全空翻整份在册预警，每行带预警编号。 */
    public Mono<PageResult<EpiAlert>> pageAlerts(int pageNum, int pageSize,
                                                 Long reportId, Long sampleId,
                                                 String alertLevel, String status) {
        return alertRepository.page(pageNum, pageSize, reportId, sampleId,
                normalize(alertLevel), normalize(status));
    }

    /** 样本得在册且检测结果是阳性：阴性、待检、不确定的样本都立不出预警。 */
    private Mono<SampleTest> requirePositiveSample(Long sampleId) {
        if (sampleId == null) {
            return Mono.error(new BizException("阳性样本不能为空"));
        }
        return sampleRepository.findById(sampleId)
                .switchIfEmpty(Mono.error(new BizException("样本不存在，不能发布预警")))
                .flatMap(sample -> {
                    if (!SampleTest.RESULT_POSITIVE.equals(sample.getResult())) {
                        return Mono.error(new BizException(
                                "只有检测结果为阳性的样本才立得了预警，该样本当前结果：" + sample.getResult()));
                    }
                    return Mono.just(sample);
                });
    }

    /** 来源观测得在册：级别起步档要读它上面抄的保护级别快照；已作废的观测连带立不了。 */
    private Mono<WildlifeObs> requireSourceObs(AbnormalReport report) {
        return obsRepository.findById(report.getObsId())
                .switchIfEmpty(Mono.error(new BizException("上报来源观测不存在或已作废，不能发布预警")));
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
