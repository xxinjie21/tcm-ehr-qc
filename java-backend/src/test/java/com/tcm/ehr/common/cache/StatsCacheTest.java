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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1 词频缓存行为：命中不重算 / 未命中回填 / Redis 故障降级 / 写操作失效（按数据域 + ALL）。
 *
 * <p>失效口径已改为<b>版本号前缀</b>（R3-③ / W3）：失效只把 {@code tcm:ehr:cache:stats:ver:{scope}}
 * 自增，数据 key 形如 {@code tcm:ehr:cache:stats:{scope}:v{n}:all:{md5}}；<b>不再调用 {@code KEYS}/{@code DEL}</b>
 * —— 那两条是 O(N) 全库扫描 + Redis 单线程阻塞，生产禁用级。本测试把「不再用 KEYS」与
 * 「失效后旧版本 key 不再被读」都钉住。</p>
 */
@DisplayName("StatsCache：词频缓存命中/失效/降级")
class StatsCacheTest {

    private static final String ORG = "org-b1-1";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private StatsCache cache;
    /** 用一个 Map 充当 Redis 的键空间：get / set / increment 都落在同一命名空间，版本号语义才测得出 */
    private final Map<String, String> store = new HashMap<>();
    private final AtomicInteger loaderCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        // set(K,V,Duration) 是 void：必须用 doAnswer 捕获回填（spring-data 还有
        // set(K,V,SetSpec) 重载，用 Duration.class 精确匹配，否则编译期“调用不明确”）
        org.mockito.Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.increment(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            long next = Long.parseLong(store.getOrDefault(k, "0")) + 1;
            store.put(k, String.valueOf(next));
            return next;
        });
        // 单飞锁：默认当「领跑者」（拿得到锁），让既有用例保持确定性；
        // 跟随者分支另有专门用例覆盖。
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
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

    private int computeOnce() {
        cache.wordFreq(new FiltersDTO(), () -> {
            loaderCalls.incrementAndGet();
            return freq();
        });
        return loaderCalls.get();
    }

    @Test
    @DisplayName("未命中 → 调 loader 并回填；再读命中 → 不重算；key 带作用域 + 版本号 + 60s TTL")
    void missComputesAndHitReuses() {
        assertEquals(1, computeOnce(), "首次应调 loader 现算");
        assertEquals(1, computeOnce(), "命中缓存后不应重算");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(key.capture(), anyString(), ttl.capture());
        assertEquals("tcm:ehr:cache:stats:" + ORG + ":v0:all:",
                key.getValue().substring(0, key.getValue().lastIndexOf(":all:") + 5),
                "key 必须带统一前缀 + 数据域作用域 + 版本号 + 统计类别");
        // TTL 带抖动（R3-①）：60 ± 10s，避免全部 key 同秒过期一起回源
        long ttlSeconds = ttl.getValue().getSeconds();
        assertTrue(ttlSeconds >= 50 && ttlSeconds <= 70, "TTL 应落在 60±10s：" + ttlSeconds);
    }

    @Test
    @DisplayName("白名单外的过滤条件绕过缓存：照常现算，但不落 key（R3-② 键空间有界）")
    void nonWhitelistedFiltersBypassCache() {
        FiltersDTO weird = new FiltersDTO();
        weird.setDepartment("随便输的科室-带奇怪字符/../x".repeat(3));
        AtomicInteger calls = new AtomicInteger();
        cache.wordFreq(weird, () -> {
            calls.incrementAndGet();
            return freq();
        });
        cache.wordFreq(weird, () -> {
            calls.incrementAndGet();
            return freq();
        });
        assertEquals(2, calls.get(), "白名单外必须每次现算，不能命中缓存");
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("并发未命中时跟随者读领跑者写好的缓存，不重复现算（R3-③ 单飞）")
    void followerReusesLeaderResult() {
        // 先让「领跑者」跑一次，把结果写进缓存并拿到 key
        assertEquals(1, computeOnce(), "领跑者现算一次");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(ops).set(keyCap.capture(), anyString(), any(Duration.class));
        String key = keyCap.getValue();

        // 模拟并发：锁被别的实例占着（跟随者），但缓存里已有领跑者的结果
        org.mockito.Mockito.clearInvocations(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        store.remove(key); // 让缓存「看起来」还没写
        store.put(key, new ObjectMapper().writeValueAsString(freq()));

        assertEquals(1, computeOnce(), "跟随者读到缓存即返回，不得再调 loader");
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
        assertNotNull(vo);
    }

    @Test
    @DisplayName("写操作失效：两个作用域各一次版本号自增，不再用 KEYS / DEL")
    void invalidateBumpsVersionForScopeAndAll() {
        cache.invalidateForWrite();

        // 当前域 + ALL 各一次 INCR
        verify(ops, org.mockito.Mockito.times(2)).increment(anyString());
        // 关键：失效路径里不得再出现 KEYS（O(N) 全库扫描 + 单线程阻塞）
        verify(redis, never()).keys(anyString());
        verify(redis, never()).delete(anyCollection());
        assertEquals("1", store.get("tcm:ehr:cache:stats:ver:" + ORG), "当前数据域版本号应自增到 1");
        assertEquals("1", store.get("tcm:ehr:cache:stats:ver:ALL"), "ALL 作用域版本号应自增到 1");
    }

    @Test
    @DisplayName("版本号自增后旧版本 key 不再命中（W3：旧值写回不再被读）")
    void bumpVersionMakesOldEntryUnreachable() {
        assertEquals(1, computeOnce(), "首次现算并回填 v0 key");

        cache.invalidateForWrite();
        assertEquals(2, computeOnce(), "失效后必须重新现算，而不是读到旧版本的值");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(ops, org.mockito.Mockito.times(2)).set(key.capture(), anyString(), any(Duration.class));
        String first = key.getAllValues().get(0);
        String second = key.getAllValues().get(1);
        assertNotEquals(first, second, "失效后写的必须是新版本 key，不能复用旧 key");
        assertTrue(first.contains(":v0:all:"), "首次落在 v0：" + first);
        assertTrue(second.contains(":v1:all:"), "失效后落在 v1：" + second);
    }
}
