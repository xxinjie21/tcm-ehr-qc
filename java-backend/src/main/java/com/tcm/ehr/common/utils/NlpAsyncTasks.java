package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.NlpExtractVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 单条 NLP 抽取的异步任务登记（AI-18）。
 *
 * <p>行为全部来自 {@link AsyncTaskRegistry}；本类只钉住三件事：共享层 key 前缀
 * {@code task:nlp:}、结果载荷类型 {@link NlpExtractVO}、以及 Spring 装配的线程池
 * {@code nlpAsyncExecutor}（见 {@code AsyncConfig}）。</p>
 *
 * <p><b>为什么单条抽取也要异步</b>：{@code NlpController.extract} 原先要同步完成
 * 「转发 Python（{@code nlp.timeout=30000ms}）+ ES 术语归一」才返回，Tomcat 请求线程
 * 被占住最长 30 秒。项目里 AI 三个端点早已是「提交拿任务号 + 轮询」，NLP 单条未跟进，
 * 同一项目两套口径；这里对齐为同一套。</p>
 */
@Component
public class NlpAsyncTasks extends AsyncTaskRegistry<NlpExtractVO> {

    public NlpAsyncTasks(@Qualifier("nlpAsyncExecutor") ThreadPoolTaskExecutor executor) {
        super("task:nlp:", NlpExtractVO.class, executor);
    }
}
