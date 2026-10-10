package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.AiReplyVO;
import org.springframework.stereotype.Component;

/**
 * AI 长响应的异步任务登记（批次 15 · 15.1）。
 *
 * <p>行为全部来自 {@link AsyncTaskRegistry}；本类只钉住两件事：共享层 key 前缀
 * {@code task:ai:} 与结果载荷类型 {@link AiReplyVO}。</p>
 *
 * <p>通用部分（请求上下文传播、跨实例共享、有界队列）见基类文档。</p>
 */
@Component
public class AiAsyncTasks extends AsyncTaskRegistry<AiReplyVO> {

    public AiAsyncTasks() {
        super("task:ai:", "task.ai", AiReplyVO.class);
    }
}
