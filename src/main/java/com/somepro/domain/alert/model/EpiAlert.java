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
 * 疫病预警聚合根（领域层）：样本检出阳性不是小事，马上立一条预警，级别按这单的来头算，
 * 后面盯着处置到解除（解除后还能归档）。一条预警一条记录。
 *
 * 业务规则：
 * - 一条预警挂一条异常上报（reportId）、挂一个阳性样本（sampleId）；
 * - 级别四档：{@link #LEVEL_BLUE 蓝} / {@link #LEVEL_YELLOW 黄} /
 *   {@link #LEVEL_ORANGE 橙} / {@link #LEVEL_RED 红}，不由前端随手填，由系统按来头算
 *   （{@link #deriveLevel}）；
 * - 状态机只顺不逆：已发布 RAISED → 处置中 HANDLING → 已解除 RESOLVED → 已归档 CLOSED；
 *   处置（{@link #handle}）只在已发布/处置中录得了，解除（{@link #resolve}）只在处置中解得了，
 *   归档（{@link #archive}）只在已解除归得了，归档是终态，再动挡回。
 *
 * 「只有阳性样本才立得了」要查样本仓储，保护级别快照要查来源观测、上报类别要查来源上报，
 * 由应用层编排把关；「同一条阳性样本只立一条预警」由仓储层在事务内行锁兜底。
 *
 * 编号 alertNo 由仓储层在落库时分配（AL-YYYY-NNNN 式），领域对象只持有不生成。
 */
@Getter
@Setter
public class EpiAlert extends BaseEntity {

    /** 级别：蓝色 */
    public static final String LEVEL_BLUE = "BLUE";
    /** 级别：黄色 */
    public static final String LEVEL_YELLOW = "YELLOW";
    /** 级别：橙色 */
    public static final String LEVEL_ORANGE = "ORANGE";
    /** 级别：红色 */
    public static final String LEVEL_RED = "RED";

    /** 状态：已发布 */
    public static final String STATUS_RAISED = "RAISED";
    /** 状态：处置中 */
    public static final String STATUS_HANDLING = "HANDLING";
    /** 状态：已解除 */
    public static final String STATUS_RESOLVED = "RESOLVED";
    /** 状态：已归档（终态） */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 级别由低到高：往上抬档就沿这个次序走一格，红色到顶不再抬。 */
    private static final List<String> LEVEL_ORDER =
            List.of(LEVEL_BLUE, LEVEL_YELLOW, LEVEL_ORANGE, LEVEL_RED);

    /**
     * 保护级别 → 预警起步档：国家一级起红、国家二级起橙、省级起黄、一般起蓝；
     * 拿不到快照（不该发生，应用层已验）也兜在蓝色。
     */
    private static final Map<String, String> PROTECTION_BASE_LEVEL = Map.of(
            Species.LEVEL_NATIONAL_ONE, LEVEL_RED,
            Species.LEVEL_NATIONAL_TWO, LEVEL_ORANGE,
            Species.LEVEL_PROVINCIAL, LEVEL_YELLOW,
            Species.LEVEL_COMMON, LEVEL_BLUE);

    private Long id;

    /** 预警编号（如 AL-2026-0001），全局唯一 */
    private String alertNo;

    /** 挂在哪条异常上报上（t_abnormal_report.id） */
    private Long reportId;

    /** 挂在哪个阳性样本上（t_sample_test.id） */
    private Long sampleId;

    /** 预警级别：BLUE / YELLOW / ORANGE / RED（系统按来头算，不由前端填） */
    private String alertLevel;

    /** 状态：RAISED / HANDLING / RESOLVED / CLOSED */
    private String status;

    /** 处置措施（处置时记下） */
    private String disposalMethod;

    /** 发布时刻 */
    private LocalDateTime raisedAt;

    /** 解除时刻 */
    private LocalDateTime resolvedAt;

    /**
     * 工厂方法：检出阳性立一条预警，立起来先落在已发布。
     *
     * @param obsProtectionLevel 来源观测上抄的保护级别快照（算起步档用，别去读名录现在改成的样子）
     * @param reportCategory     来源上报类别（死亡/疑似疫病抬一档，受伤不抬）
     * @param raisedAt           发布时刻，null 时取发布当下
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
     * 处置：已发布 → 处置中，记下处置措施；已在处置中的可继续补记/改写措施，状态不二次翻动。
     * 已解除、已归档的不再收处置。落库走按原状态条件更新，并发只放行一下。
     */
    public void handle(String disposalMethod) {
        if (disposalMethod == null || disposalMethod.isBlank()) {
            throw new BizException("处置措施不能为空");
        }
        if (STATUS_RESOLVED.equals(this.status) || STATUS_CLOSED.equals(this.status)) {
            throw new BizException("预警已解除或已归档，不能再处置");
        }
        if (!STATUS_RAISED.equals(this.status) && !STATUS_HANDLING.equals(this.status)) {
            throw new BizException("只有已发布、处置中的预警才处置得了");
        }
        this.disposalMethod = disposalMethod.trim();
        this.status = STATUS_HANDLING;
    }

    /**
     * 解除：处置中 → 已解除，解除时刻落下来。还没开处置的不能直接解（处置措施得先记下），
     * 已解除/已归档的重复解除挡回。落库走按原状态条件更新，并发只放行一下。
     *
     * @param resolvedAt 解除时刻，null 时取解除当下
     */
    public void resolve(LocalDateTime resolvedAt) {
        if (!STATUS_HANDLING.equals(this.status)) {
            throw new BizException("只有处置中的预警才解除得了：先处置记下措施，已解除/已归档的不再解除");
        }
        this.status = STATUS_RESOLVED;
        this.resolvedAt = resolvedAt != null ? resolvedAt : LocalDateTime.now();
    }

    /** 归档：已解除 → 已归档。归档是终态，再处置、再解除、再归档一律挡回。 */
    public void archive() {
        if (!STATUS_RESOLVED.equals(this.status)) {
            throw new BizException("只有已解除的预警才归档得了");
        }
        this.status = STATUS_CLOSED;
    }

    /**
     * 级别按这单的来头算：
     * 先看来源观测上抄的保护级别快照定起步档（国家一级起红、国家二级起橙、省级起黄、一般起蓝），
     * 再看来源上报类别，死亡/疑似疫病在起步档上往上抬一档（蓝→黄→橙→红，红色到顶不再抬），
     * 受伤不抬。
     */
    public static String deriveLevel(String obsProtectionLevel, String reportCategory) {
        String base = PROTECTION_BASE_LEVEL.getOrDefault(obsProtectionLevel, LEVEL_BLUE);
        if (AbnormalReport.CATEGORY_DEAD.equals(reportCategory)
                || AbnormalReport.CATEGORY_SUSPECT_DISEASE.equals(reportCategory)) {
            int idx = LEVEL_ORDER.indexOf(base);
            return LEVEL_ORDER.get(Math.min(idx + 1, LEVEL_ORDER.size() - 1));
        }
        return base;
    }
}
