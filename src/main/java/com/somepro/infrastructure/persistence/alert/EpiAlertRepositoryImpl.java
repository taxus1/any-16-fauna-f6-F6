package com.somepro.infrastructure.persistence.alert;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.alert.repository.EpiAlertRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.alert.converter.EpiAlertPoConverter;
import com.somepro.infrastructure.persistence.alert.po.EpiAlertPO;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.sample.SampleTestMapper;
import com.somepro.infrastructure.persistence.support.BizNoGenerator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 疫病预警仓储适配器（基础设施层）。
 *
 * 编号分配：alertNo 按 AL-YYYY-NNNN 生成（年份按发布当下，序号 4 位零填充），
 * 并发撞号由 {@link BizNoGenerator} 重试，唯一索引兜底，一个号只落一条；
 * 占用的编号不复用（selectMaxSeq 的自定义 @Select 不拼 del_flag）。
 *
 * 「同一条阳性样本只立一条预警」在发布事务内兜底：先 SELECT ... FOR UPDATE 锁住样本那一行，
 * 再数该样本名下在册预警、落库 —— 重复递进在锁上排队，等前面那条提交后数到已有一条，
 * 报业务失败，只留一条。
 *
 * 处置/解除/归档都走「按原状态条件更新」：并发动同一条只有一下翻得动，状态与时刻不二次翻动。
 */
@Repository
public class EpiAlertRepositoryImpl implements EpiAlertRepository {

    /** 编号前缀：AL-（完整形如 AL-2026-） */
    private static final String NO_PREFIX = "AL-";

    private final EpiAlertMapper alertMapper;
    private final SampleTestMapper sampleMapper;
    private final TransactionTemplate transactionTemplate;

    public EpiAlertRepositoryImpl(EpiAlertMapper alertMapper,
                                  SampleTestMapper sampleMapper,
                                  PlatformTransactionManager transactionManager) {
        this.alertMapper = alertMapper;
        this.sampleMapper = sampleMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<EpiAlert> create(EpiAlert alert) {
        return blocking(() -> transactionTemplate.execute(txStatus -> {
            // 锁住阳性样本那一行：同一样本的重复发布在这里排队，锁随事务提交/回滚释放
            sampleMapper.lockById(alert.getSampleId());
            // 数在册预警（@TableLogic 自动拼 del_flag=0）：同一条阳性样本已立过就不许再落
            Long active = alertMapper.selectCount(Wrappers.<EpiAlertPO>lambdaQuery()
                    .eq(EpiAlertPO::getSampleId, alert.getSampleId()));
            if (active != null && active > 0) {
                throw new BizException("该阳性样本已立过一条预警，不能重复发布");
            }
            String prefix = NO_PREFIX + LocalDate.now().getYear() + "-";
            return BizNoGenerator.insertWithRetry(
                    () -> alertMapper.selectMaxSeq(prefix, prefix.length() + 1),
                    prefix,
                    no -> doInsert(alert, no));
        }));
    }

    @Override
    public Mono<EpiAlert> findById(Long id) {
        return blocking(() -> {
            EpiAlertPO po = alertMapper.selectById(id);
            return po == null ? null : EpiAlertPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<Boolean> handle(EpiAlert alert, String fromStatus) {
        return blocking(() -> {
            // 条件更新：只有仍处原状态（已发布/处置中）的那一行才翻得动，并发处置只放行一下；
            // SET 带新状态与处置措施，del_flag=0 由 @TableLogic 拼上。
            EpiAlertPO po = new EpiAlertPO();
            po.setStatus(alert.getStatus());
            po.setDisposalMethod(alert.getDisposalMethod());
            int rows = alertMapper.update(po, Wrappers.<EpiAlertPO>lambdaUpdate()
                    .eq(EpiAlertPO::getId, alert.getId())
                    .eq(EpiAlertPO::getStatus, fromStatus));
            return rows == 1;
        });
    }

    @Override
    public Mono<Boolean> resolve(EpiAlert alert) {
        return blocking(() -> {
            // 只有处置中的那一行才解得掉，解除时刻随 SET 一起落，并发解除只放行一下。
            EpiAlertPO po = new EpiAlertPO();
            po.setStatus(EpiAlert.STATUS_RESOLVED);
            po.setResolvedAt(alert.getResolvedAt());
            int rows = alertMapper.update(po, Wrappers.<EpiAlertPO>lambdaUpdate()
                    .eq(EpiAlertPO::getId, alert.getId())
                    .eq(EpiAlertPO::getStatus, EpiAlert.STATUS_HANDLING));
            return rows == 1;
        });
    }

    @Override
    public Mono<Boolean> archive(Long id) {
        return blocking(() -> {
            // 只有已解除的那一行才归档得了，归档是终态。
            EpiAlertPO po = new EpiAlertPO();
            po.setStatus(EpiAlert.STATUS_CLOSED);
            int rows = alertMapper.update(po, Wrappers.<EpiAlertPO>lambdaUpdate()
                    .eq(EpiAlertPO::getId, id)
                    .eq(EpiAlertPO::getStatus, EpiAlert.STATUS_RESOLVED));
            return rows == 1;
        });
    }

    @Override
    public Mono<PageResult<EpiAlert>> page(int pageNum, int pageSize,
                                           Long reportId, Long sampleId,
                                           String alertLevel, String status) {
        return this.<PageResult<EpiAlert>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<EpiAlertPO> wrapper = Wrappers.<EpiAlertPO>lambdaQuery()
                        .eq(reportId != null, EpiAlertPO::getReportId, reportId)
                        .eq(sampleId != null, EpiAlertPO::getSampleId, sampleId)
                        .eq(hasText(alertLevel), EpiAlertPO::getAlertLevel, alertLevel)
                        .eq(hasText(status), EpiAlertPO::getStatus, status)
                        .orderByAsc(EpiAlertPO::getId);
                List<EpiAlertPO> rows = alertMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<EpiAlert> content = rows.stream()
                        .map(EpiAlertPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    private EpiAlert doInsert(EpiAlert alert, String alertNo) {
        alert.setAlertNo(alertNo);
        EpiAlertPO po = EpiAlertPoConverter.toPo(alert);
        po.setId(IdUtil.getSnowflakeNextId());
        alertMapper.insert(po);
        return EpiAlertPoConverter.toDomain(po);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 阻塞 DB 调用 → 响应式链路的桥接器：先取 Reactor Context 里的操作人，
     * 再切到 boundedElastic 执行 JDBC，操作人放进 AuditContextHolder 供审计填充。
     */
    private <T> Mono<T> blocking(Supplier<T> supplier) {
        return Mono.deferContextual(ctx -> {
            String operator = ReactiveOperatorContext.getOperator(ctx);
            return Mono.fromCallable(() -> {
                AuditContextHolder.setOperator(operator);
                try {
                    return supplier.get();
                } finally {
                    AuditContextHolder.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
