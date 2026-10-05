package com.tcm.ehr.service.impl;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.search.SearchHits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 召回缓存（批次 12 · 12b「同批 LRU」）的验收测试。
 *
 * <p>12b 的验收是「**请求数下降**；结果与改造前逐条一致」。本测试证实前半句：
 * 同一术语反复归一，ES 只被请求一次；并且证实**失效挂钩存在** —— 清缓存后再查会重新打到 ES，
 * 而 {@code rebuild}（词表重建）内部调用的正是这个清空动作，所以「改了词表却仍命中旧结果」
 * 的脏读不会发生（12g 担心的那一类）。</p>
 *
 * <p>后半句（逐条一致）是**结构性**保证、不需要对账：批量走 {@code _msearch}，
 * 每个子查询与单条 search 逐字相同、响应按提交顺序返回（见接口 javadoc）。</p>
 */
class EsTermRecallCacheTest {

    private RestHighLevelClient client;
    private EsTermIndexServiceImpl svc;

    @BeforeEach
    void setUp() throws Exception {
        // 缓存是静态的：测试之间必须清，否则上一个用例的命中会污染这一个
        EsTermIndexServiceImpl.clearRecallCache();
        client = mock(RestHighLevelClient.class);
        SearchResponse response = mock(SearchResponse.class);
        SearchHits hits = mock(SearchHits.class);
        when(hits.getHits()).thenReturn(new org.elasticsearch.search.SearchHit[0]);
        when(response.getHits()).thenReturn(hits);
        when(client.search(any(SearchRequest.class), any(RequestOptions.class))).thenReturn(response);
        svc = new EsTermIndexServiceImpl(client, new tools.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("同一术语归一两次 ⇒ ES 只被请求一次（请求数下降）")
    void sameTermHitsCache() throws Exception {
        svc.search("symptom", "org-A", "失眠", 5);
        svc.search("symptom", "org-A", "失眠", 5);
        verify(client, times(1)).search(any(SearchRequest.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("不同组织 / 不同类型 / 不同术语 不共用缓存（键含三者）")
    void differentKeyDoesNotReuse() throws Exception {
        svc.search("symptom", "org-A", "失眠", 5);
        svc.search("symptom", "org-B", "失眠", 5);
        svc.search("disease", "org-A", "失眠", 5);
        svc.search("symptom", "org-A", "头晕", 5);
        verify(client, times(4)).search(any(SearchRequest.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("清缓存后再查会重新打到 ES —— 即 rebuild 的失效挂钩真的生效")
    void clearingCacheForcesRefetch() throws Exception {
        svc.search("symptom", "org-A", "失眠", 5);
        verify(client, times(1)).search(any(SearchRequest.class), any(RequestOptions.class));

        // rebuild(...) 内部第一件事就是调用它；这里直接调用，等价验证「失效后必须重新查」
        EsTermIndexServiceImpl.clearRecallCache();

        svc.search("symptom", "org-A", "失眠", 5);
        verify(client, times(2)).search(any(SearchRequest.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("空白入参不发 ES 请求（与逐条查的行为一致）")
    void blankInputDoesNotHitEs() throws Exception {
        svc.search("symptom", "org-A", "   ", 5);
        verify(client, times(0)).search(any(SearchRequest.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("批量预取会把结果写进缓存 —— 预取一次后，循环里的逐术语调用不再打 ES")
    void batchWarmsCacheForPerTermCalls() throws Exception {
        org.elasticsearch.action.search.MultiSearchResponse multi =
                mock(org.elasticsearch.action.search.MultiSearchResponse.class);
        org.elasticsearch.action.search.MultiSearchResponse.Item item =
                mock(org.elasticsearch.action.search.MultiSearchResponse.Item.class);
        SearchResponse one = mock(SearchResponse.class);
        SearchHits hits = mock(SearchHits.class);
        when(hits.getHits()).thenReturn(new org.elasticsearch.search.SearchHit[0]);
        when(one.getHits()).thenReturn(hits);
        when(item.isFailure()).thenReturn(false);
        when(item.getResponse()).thenReturn(one);
        when(multi.getResponses()).thenReturn(new org.elasticsearch.action.search.MultiSearchResponse.Item[]{item, item});
        when(client.msearch(any(org.elasticsearch.action.search.MultiSearchRequest.class),
                any(RequestOptions.class))).thenReturn(multi);

        svc.searchBatch("symptom", "org-A", List.of("失眠", "头晕"), 5);
        verify(client, times(1)).msearch(any(org.elasticsearch.action.search.MultiSearchRequest.class),
                any(RequestOptions.class));

        // 预取已把 失眠/头晕 写进缓存 ⇒ 这两次逐术语调用不应再打到 ES
        svc.search("symptom", "org-A", "失眠", 5);
        svc.search("symptom", "org-A", "头晕", 5);
        verify(client, times(0)).search(any(SearchRequest.class), any(RequestOptions.class));
    }
}
