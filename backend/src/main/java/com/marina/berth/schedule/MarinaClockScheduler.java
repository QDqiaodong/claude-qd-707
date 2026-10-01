package com.marina.berth.schedule;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.marina.berth.service.ClosureService;
import com.marina.berth.service.SailingPlanService;

/**
 * 码头时钟，每 5 秒推进两件事：
 * <ol>
 *   <li>到点的「待生效」封航令自动生效（暂停申报 + 生成返港处置名单）；</li>
 *   <li>到出航时间仍「已批准」的申报自动办理出港（结算离泊、船只转出海）。</li>
 * </ol>
 * 出航审批接口还会对封航做一次同事务懒生效兜底，保证「发布时间已到且仍生效中」时批准穿不过闸门。
 */
@Component
public class MarinaClockScheduler {

    private final ClosureService closureService;
    private final SailingPlanService planService;

    public MarinaClockScheduler(ClosureService closureService, SailingPlanService planService) {
        this.closureService = closureService;
        this.planService = planService;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 3000)
    public void tick() {
        closureService.activateDueOrders();
        planService.processDueDepartures();
    }
}
