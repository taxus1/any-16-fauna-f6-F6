package com.somepro.domain.alert.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.report.model.AbnormalReport;
import com.somepro.domain.shared.model.BaseEntity;
import com.somepro.domain.species.model.Species;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 疫病预警聚合根（领域层）：样本检出阳性不是小事，马上立一条预警，
 * 级别按这单的来头算，后面处置、解除都顺着这条记录走。一条预警一条记录。
 *
 * 业务规则：
 * - 一条预警挂在哪条上报（reportId）、哪个阳性样本（sampleId）上都要记下；
 * - 预警级别四档：{@link #LEVEL_BLUE 蓝} / {@link #LEVEL_YELLOW 黄} /
 *   {@link #LEVEL_ORANGE 橙} / {@link #LEVEL_RED 红}；
 * - 级别不由前端随手填，按这单的来头系统算：
 *   先看观测上抄下来的物种保护级别快照 —— 国家一级起在红、国家二级起在橙、
 *   省级起在黄、一般起在蓝；再看上报类别，死亡/疑似疫病的往上抬一档
 *   （蓝→黄、黄→橙、橙→红，红到顶不再抬），受伤不抬；
 *   保护级别取观测上的快照，不读名录现在改成的样子（应用层把关取值来源）；
 * - 状态：{@link #STATUS_RAISED 已发布} → {@link #STATUS_HANDLING 处置中}
 *   → {@link #STATUS_RESOLVED 已解除}；{@link #STATUS_CLOSED 已归档} 是留给归档的终态，
 *   发布后只能顺着走，不能跳级（已发布不能直接解除，得先处置）不能回退，已解除再动挡回；
 * - 处置时记下处置措施（disposalMethod）；解除时落下解除时刻。
 *
 * 「只有阳性样本才立得了、同一阳性样本只立一条」要查样本/上报/观测仓储，
 * 由应用层编排把关（重复立预警在仓储层事务内兜底）；领域对象只保证自身字段不变量。
 *
 * 编号 alertNo 由仓储层在落库时分配（AL-YYYY-NNNN 式），领域对象只持有不生成。
 */
@Getter
@Setter
public class EpiAlert extends BaseEntity {

    /** 预警级别：蓝色 */
    public static final String LEVEL_BLUE = "BLUE";
    /** 预警级别：黄色 */
    public static final String LEVEL_YELLOW = "YELLOW";
    /** 预警级别：橙色 */
    public static final String LEVEL_ORANGE = "ORANGE";
    /** 预警级别：红色 */
    public static final String LEVEL_RED = "RED";

    /** 状态：已发布（立起来的起点） */
    public static final String STATUS_RAISED = "RAISED";
    /** 状态：处置中 */
    public static final String STATUS_HANDLING = "HANDLING";
    /** 状态：已解除（处置走完的终态） */
    public static final String STATUS_RESOLVED = "RESOLVED";
    /** 状态：已归档（留给归档的终态） */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 保护级别快照 → 起评档：国家一级起红、国家二级起橙、省级起黄、一般起蓝。 */
    private static final Map<String, String> BASE_LEVEL_BY_PROTECTION = Map.of(
            Species.LEVEL_NATIONAL_ONE, LEVEL_RED,
            Species.LEVEL_NATIONAL_TWO, LEVEL_ORANGE,
            Species.LEVEL_PROVINCIAL, LEVEL_YELLOW,
            Species.LEVEL_COMMON, LEVEL_BLUE);

    /** 抬档次序：蓝→黄、黄→橙、橙→红，红到顶不再抬。 */
    private static final Map<String, String> NEXT_LEVEL = Map.of(
            LEVEL_BLUE, LEVEL_YELLOW,
            LEVEL_YELLOW, LEVEL_ORANGE,
            LEVEL_ORANGE, LEVEL_RED);

    /** 死亡/疑似疫病才抬档，受伤不抬。 */
    private static final List<String> ESCALATE_CATEGORIES =
            List.of(AbnormalReport.CATEGORY_DEAD, AbnormalReport.CATEGORY_SUSPECT_DISEASE);

    private Long id;

    /** 预警编号（如 AL-2026-0001），全局唯一 */
    private String alertNo;

    /** 挂在哪条上报上（t_abnormal_report.id） */
    private Long reportId;

    /** 挂在哪个阳性样本上（t_sample_test.id） */
    private Long sampleId;

    /** 预警级别：BLUE / YELLOW / ORANGE / RED（系统按来头算，不由前端填） */
    private String alertLevel;

    /** 状态：RAISED / HANDLING / RESOLVED / CLOSED */
    private String status;

    /** 处置措施（处置时记下） */
    private String disposalMethod;

    /** 预警发布时刻（不传则取发布当下） */
    private LocalDateTime raisedAt;

    /** 解除时刻（处置走完解除时落下） */
    private LocalDateTime resolvedAt;

    /**
     * 工厂方法：给一条阳性样本立一条预警，级别按这单的来头算，立起来落在已发布。
     *
     * @param reportId            挂在哪条上报
     * @param sampleId            挂在哪个阳性样本
     * @param obsProtectionLevel  观测上抄的保护级别快照（不读名录现在的级别）
     * @param reportCategory      上报类别 INJURED/DEAD/SUSPECT_DISEASE
     * @param raisedAt            发布时刻，null 时取发布当下
     */
    public static EpiAlert raise(Long reportId, Long sampleId, String obsProtectionLevel,
                                 String reportCategory, LocalDateTime raisedAt) {
        EpiAlert alert = new EpiAlert();
        alert.attachReport(reportId);
        alert.attachSample(sampleId);
        alert.alertLevel = deriveLevel(obsProtectionLevel, reportCategory);
        alert.status = STATUS_RAISED;
        alert.raisedAt = raisedAt != null ? raisedAt : LocalDateTime.now();
        return alert;
    }

    public void attachReport(Long reportId) {
        if (reportId == null) {
            throw new BizException("来源上报不能为空");
        }
        this.reportId = reportId;
    }

    public void attachSample(Long sampleId) {
        if (sampleId == null) {
            throw new BizException("阳性样本不能为空");
        }
        this.sampleId = sampleId;
    }

    /**
     * 开始处置：已发布 → 处置中，处置措施当场记下（措施不能为空话）。
     * 处置中再补措施也走这里（状态不动、只更新措施）；已解除/已归档是终态，动不了。
     */
    public void handle(String disposalMethod) {
        if (disposalMethod == null || disposalMethod.isBlank()) {
            throw new BizException("处置措施不能为空");
        }
        if (STATUS_RESOLVED.equals(this.status) || STATUS_CLOSED.equals(this.status)) {
            throw new BizException("预警已解除，不能再处置");
        }
        if (!STATUS_RAISED.equals(this.status) && !STATUS_HANDLING.equals(this.status)) {
            throw new BizException("预警状态已变化，请刷新后重试");
        }
        this.disposalMethod = disposalMethod.trim();
        this.status = STATUS_HANDLING;
    }

    /**
     * 解除：处置中 → 已解除，解除时刻落下（不传取当下）。
     * 已发布还没处置的解不了（不能跳级），已解除/已归档再解挡回（不能回退/重复）。
     */
    public void resolve(LocalDateTime resolvedAt) {
        if (STATUS_RESOLVED.equals(this.status) || STATUS_CLOSED.equals(this.status)) {
            throw new BizException("预警已解除，不能重复解除");
        }
        if (!STATUS_HANDLING.equals(this.status)) {
            throw new BizException("预警还没进入处置中，不能直接解除：已发布→处置中→已解除，不能跳级");
        }
        this.status = STATUS_RESOLVED;
        this.resolvedAt = resolvedAt != null ? resolvedAt : LocalDateTime.now();
    }

    /**
     * 级别按来头算：保护级别快照定起评档，死亡/疑似疫病抬一档（红到顶不再抬），受伤不抬。
     */
    private static String deriveLevel(String obsProtectionLevel, String reportCategory) {
        String base = BASE_LEVEL_BY_PROTECTION.get(obsProtectionLevel);
        if (base == null) {
            throw new BizException("观测保护级别快照非法，算不出预警级别");
        }
        if (reportCategory != null && ESCALATE_CATEGORIES.contains(reportCategory.trim())) {
            return NEXT_LEVEL.getOrDefault(base, LEVEL_RED);
        }
        return base;
    }
}
