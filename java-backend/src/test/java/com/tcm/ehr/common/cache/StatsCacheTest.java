package com.tcm.ehr.common.cache;

import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.vo.StatsAllVO;
import com.tcm.ehr.domain.vo.StatsVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1 词频缓存行为：命中不重算 / 未命中回填 / Redis 故障降级 / 写操作失效（按数据域 + ALL）。
 */
@DisplayName("StatsCache：词频缓存命中/失效/降级")
class StatsCacheTest {

    private static final String ORG = "org-b1-1";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private StatsCache cache;
    private final AtomicReference<String> storedJson = new AtomicReference<>();
    private final AtomicInteger loaderCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // 模拟 Redis 的 get：已缓存返回 JSON，未缓存返回 null
        when(ops.get(anyString())).thenAnswer(inv -> storedJson.get());
        cache = new StatsCache(redis, new ObjectMapper());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(contextReq()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest contextReq() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", ORG);
        req.setAttribute("currentOrgRole", "member");
        return req;
    }

    private static StatsAllVO freq() {
        StatsAllVO vo = new StatsAllVO();
        StatsVO s = new StatsVO();
        vo.setSymptom(s);
        return vo;
    }

    private void fillCacheFromLoader() {
        // set(K,V,Duration) 是 void：必须用 doAnswer 捕获回填的 JSON（spring-data 还有
        // set(K,V,SetSpec) 重载，用 Duration.class 精确匹配，否则编译期“调用不明确”）
        org.mockito.Mockito.doAnswer(inv -> {
            storedJson.set(inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("未命中 → 调 loader 并回填；再读命中 → 不重算")
    void missComputesAndHitReuses() throws Exception {
        fillCacheFromLoader();
        StatsAllVO first = cache.wordFreq(new FiltersDTO(), () -> {
            loaderCalls.incrementAndGet();
            return freq();
        });
        assertEquals(1, loaderCalls.get(), "首次应调 loader 现算");

        StatsAllVO second = cache.wordFreq(new FiltersDTO(), () -> {
            loaderCalls.incrementAndGet();
            return freq();
        });
        assertEquals(1, loaderCalls.get(), "命中缓存后不应重算");

        // key 必须带数据域作用域 + TTL=60s
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(key.capture(), anyString(), ttl.capture());
        assertEquals("cache:stats:" + ORG + ":all:",
                key.getValue().substring(0, key.getValue().lastIndexOf(":all:") + 5),
                "key 必须带数据域作用域 + 统计类别");
        assertEquals(Duration.ofSeconds(60), ttl.getValue());
    }

    @Test
    @DisplayName("Redis 读故障 → 直接走 loader 现算，不阻塞")
    void redisReadFailureDegradesToCompute() {
        org.mockito.Mockito.reset(redis);
        when(redis.opsForValue()).thenThrow(new RuntimeException("连接拒绝"));
        AtomicInteger calls = new AtomicInteger();
        StatsAllVO vo = cache.wordFreq(new FiltersDTO(), () -> {
            calls.incrementAndGet();
            return freq();
        });
        assertEquals(1, calls.get(), "故障时仍应返回现算结果");
        org.junit.jupiter.api.Assertions.assertNotNull(vo);
    }

    @Test
    @DisplayName("写操作失效：清当前数据域 + ALL 两个作用域")
    void invalidateClearsScopeAndAll() {
        when(redis.keys(anyString())).thenReturn(
                Set.of("cache:stats:" + ORG + ":all:abc", "cache:stats:" + ORG + ":all:def"));
        when(redis.delete(anyCollection())).thenReturn(2L);

        cache.invalidateForWrite();

        // 两个作用域各 keys/delete 一次：当前域 + ALL
        org.mockito.Mockito.verify(redis, org.mockito.Mockito.times(2)).keys(anyString());
        org.mockito.Mockito.verify(redis, org.mockito.Mockito.times(2)).delete(anyCollection());
    }
}