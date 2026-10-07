package com.somepro.domain.alert.repository;

import com.somepro.domain.alert.model.EpiAlert;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * 疫病预警的仓储端口（领域层定义，基础设施层实现）。
 */
public interface EpiAlertRepository {

    /**
     * 发布预警落库；alertNo 由实现侧按 AL-YYYY-NNNN 生成，并发撞号自动重取，不甩底层冲突。
     * 同一条阳性样本只落一条：实现侧在事务内锁住样本行兜底，重复递进只留一条。
     */
    Mono<EpiAlert> create(EpiAlert alert);

    /** 按 id 查看在册预警（del_flag=0）。 */
    Mono<EpiAlert> findById(Long id);

    /**
     * 处置落库：已发布（或处置中补措施）按原状态条件更新到处置中并写处置措施，
     * 并发处置同一单只放行一下。
     *
     * @return 是否翻得动（false = 状态已被并发动作改动）
     */
    Mono<Boolean> handle(EpiAlert alert, String fromStatus);

    /**
     * 解除落库：处置中按原状态条件更新到已解除并落解除时刻，
     * 并发解除同一单只放行一下。
     *
     * @return 是否翻得动（false = 状态已被并发动作改动）
     */
    Mono<Boolean> resolve(EpiAlert alert, String fromStatus);

    /**
     * 条件分页：上报/样本/级别/状态随意拼，全空翻整份在册预警，每行带预警编号。
     */
    Mono<PageResult<EpiAlert>> page(int pageNum, int pageSize,
                                   Long reportId, Long sampleId, String alertLevel, String status);
}
