package com.marina.berth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时调度：封航令「发布时间已到」时由定时任务把 待生效 推进到 生效中
 * （申报创建/审批路径上还有一次惰性激活兜底，见 ClosureService#currentBlockingOrder）。
 */
@EnableScheduling
@SpringBootApplication
public class MarinaBerthApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarinaBerthApplication.class, args);
    }
}
