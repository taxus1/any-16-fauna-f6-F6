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
     * 「同一条阳性样本只立一条预警」在实现侧事务内行锁兜底：重复递进只落一条，后到者抛业务异常。
     */
    Mono<EpiAlert> create(EpiAlert alert);

    /**
     * 按 id 查看在册预警（del_flag=0）。翻不到返回空。
     */
    Mono<EpiAlert> findById(Long id);

    /**
     * 处置落库：按原状态条件更新（WHERE id=? AND status=fromStatus），只带状态与处置措施，
     * 并发处置同一单只有一下翻得动，另一下返回 false。
     *
     * @param fromStatus 处置前的原状态（应用层加载时读到的那个：RAISED / HANDLING）
     * @return true 翻动成功；false 预警已不在原状态（被并发翻动）
     */
    Mono<Boolean> handle(EpiAlert alert, String fromStatus);

    /**
     * 解除落库：按处置中原状态条件更新（WHERE id=? AND status=HANDLING），
     * 只带状态与解除时刻，并发解除只有一下翻得动。
     *
     * @return true 翻动成功；false 预警已不在处置中
     */
    Mono<Boolean> resolve(EpiAlert alert);

    /**
     * 归档落库：按已解除原状态条件更新（WHERE id=? AND status=RESOLVED）。
     *
     * @return true 翻动成功；false 预警已不在已解除
     */
    Mono<Boolean> archive(Long id);

    /**
     * 条件分页：上报/样本/级别/状态随意拼，全空翻整份在册预警，每行带预警编号。
     */
    Mono<PageResult<EpiAlert>> page(int pageNum, int pageSize,
                                    Long reportId, Long sampleId, String alertLevel, String status);
}
