package com.somepro.interfaces.rest.alert;

import com.somepro.application.alert.EpiAlertAppService;
import com.somepro.common.Result;
import com.somepro.interfaces.rest.alert.converter.AlertVoConverter;
import com.somepro.interfaces.rest.alert.vo.AlertHandleRequest;
import com.somepro.interfaces.rest.alert.vo.AlertRaiseRequest;
import com.somepro.interfaces.rest.alert.vo.AlertVO;
import com.somepro.interfaces.rest.common.vo.PageVO;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * 疫病预警接口（用户接口层）：预警的生成、处置、解除、归档、查看，再带上按条件翻预警。
 *
 * 只做协议适配与 VO 转换，业务编排交给应用层。分页条件 reportId/sampleId/level/status 都可空，
 * 全空时翻整份在册预警，每行都带预警编号。
 */
@RestController
@RequestMapping("/api/alerts")
public class EpiAlertController {

    private final EpiAlertAppService alertAppService;

    public EpiAlertController(EpiAlertAppService alertAppService) {
        this.alertAppService = alertAppService;
    }

    /** 发布预警：只递阳性样本 id；编号 AL-YYYY-NNNN 服务端生成，级别系统按来头算。 */
    @PostMapping
    public Mono<Result<AlertVO>> raise(@Valid @RequestBody AlertRaiseRequest req) {
        return alertAppService.raise(req.sampleId(), req.raisedAt())
                .map(AlertVoConverter::toVo)
                .map(Result::ok);
    }

    @GetMapping("/{id}")
    public Mono<Result<AlertVO>> detail(@PathVariable Long id) {
        return alertAppService.detail(id)
                .map(AlertVoConverter::toVo)
                .map(Result::ok);
    }

    /** 处置：已发布→处置中并记下处置措施（处置中可继续补记）；已解除/已归档不再收。 */
    @PostMapping("/{id}/handle")
    public Mono<Result<AlertVO>> handle(@PathVariable Long id,
                                        @Valid @RequestBody AlertHandleRequest req) {
        return alertAppService.handle(id, req.disposalMethod())
                .map(AlertVoConverter::toVo)
                .map(Result::ok);
    }

    /** 解除：处置中→已解除，解除时刻落下来（不传取解除当下）。 */
    @PostMapping("/{id}/resolve")
    public Mono<Result<AlertVO>> resolve(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime resolvedAt) {
        return alertAppService.resolve(id, resolvedAt)
                .map(AlertVoConverter::toVo)
                .map(Result::ok);
    }

    /** 归档：已解除→已归档，归档是终态。 */
    @PostMapping("/{id}/archive")
    public Mono<Result<AlertVO>> archive(@PathVariable Long id) {
        return alertAppService.archive(id)
                .map(AlertVoConverter::toVo)
                .map(Result::ok);
    }

    /** 预警分页：reportId/sampleId/level/status 条件随意拼，全空翻整份在册预警。 */
    @GetMapping({"", "/list"})
    public Mono<Result<PageVO<AlertVO>>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long reportId,
            @RequestParam(required = false) Long sampleId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status) {
        return alertAppService.pageAlerts(pageNum, pageSize, reportId, sampleId, level, status)
                .map(AlertVoConverter::toPageVo)
                .map(Result::ok);
    }
}
