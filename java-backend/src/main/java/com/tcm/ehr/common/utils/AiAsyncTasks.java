package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.AiReplyVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * AI 长响应的异步任务登记（批次 15 · 15.1）。
 *
 * 行为全部来自 {@link AsyncTaskRegistry}；本类只钉住三件事：共享层 key 前缀
 * {@code task:ai:}、结果载荷类型 {@link AiReplyVO}、以及 Spring 装配的线程池
 * aiAsyncExecutor（见 AsyncConfig）。
 *
 * 通用部分（请求上下文传播、跨实例共享、有界队列）见基类文档。
 */
@Component
public class AiAsyncTasks extends AsyncTaskRegistry<AiReplyVO> {

    public AiAsyncTasks(@Qualifier("aiAsyncExecutor") ThreadPoolTaskExecutor executor) {
        super("task:ai:", AiReplyVO.class, executor);
    }
}
