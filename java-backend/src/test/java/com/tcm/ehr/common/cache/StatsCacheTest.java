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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("通用 getOrLoad：未命中现算并回填，再读命中不重算；写操作失效后重新现算")
    void getOrLoadComputesThenHitsThenInvalidates() {
        AtomicInteger calls = new AtomicInteger();
        cache.getOrLoad("extra", null, Duration.ofSeconds(60),
                () -> { calls.incrementAndGet(); return new StatsVO(); }, StatsVO.class);
        cache.getOrLoad("extra", null, Duration.ofSeconds(60),
                () -> { calls.incrementAndGet(); return new StatsVO(); }, StatsVO.class);
        assertEquals(1, calls.get(), "第二次应命中缓存，不再现算");

        cache.invalidateForWrite();
        cache.getOrLoad("extra", null, Duration.ofSeconds(60),
                () -> { calls.incrementAndGet(); return new StatsVO(); }, StatsVO.class);
        assertEquals(2, calls.get(), "写操作失效后必须重新现算（全局版本段自增）");
    }

    @Test
    @DisplayName("通用 getOrLoad：salt（词典版本）变化 → 换 key、不复用旧结果")
    void getOrLoadSaltChangesKey() {
        AtomicInteger calls = new AtomicInteger();
        cache.getOrLoad("report", Map.of("start", "2024-01-01"), "dict-v1", Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return new StatsVO(); }, StatsVO.class);
        cache.getOrLoad("report", Map.of("start", "2024-01-01"), "dict-v1", Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return new StatsVO(); }, StatsVO.class);
        assertEquals(1, calls.get(), "同 salt 应命中");
        cache.getOrLoad("report", Map.of("start", "2024-01-01"), "dict-v2", Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return new StatsVO(); }, StatsVO.class);
        assertEquals(2, calls.get(), "salt（词典版本）变了必须重算——否则换词表后仍读旧数");
    }

    @Test
    @DisplayName("通用 getOrLoad：FiltersDTO 白名单外绕过缓存（照常现算、不落 key）")
    void getOrLoadNonWhitelistedBypasses() {
        FiltersDTO weird = new FiltersDTO();
        weird.setDepartment("x/../y".repeat(20));
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            cache.getOrLoad("extra", weird, Duration.ofSeconds(60),
                    () -> { calls.incrementAndGet(); return new StatsVO(); }, StatsVO.class);
        }
        assertEquals(2, calls.get(), "白名单外必须每次现算，不能命中缓存");
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
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
    @DisplayName("写操作失效：三处版本号各一次自增（当前域 + ALL + 全局 kind），不再用 KEYS / DEL")
    void invalidateBumpsVersionForScopeAndAll() {
        cache.invalidateForWrite();

        // 当前域 + ALL + 全局（通用 kind 用）各一次 INCR
        verify(ops, org.mockito.Mockito.times(3)).increment(anyString());
        // 关键：失效路径里不得再出现 KEYS（O(N) 全库扫描 + 单线程阻塞）
        verify(redis, never()).keys(anyString());
        verify(redis, never()).delete(anyCollection());
        assertEquals("1", store.get("tcm:ehr:cache:stats:ver:" + ORG), "当前数据域版本号应自增到 1");
        assertEquals("1", store.get("tcm:ehr:cache:stats:ver:ALL"), "ALL 作用域版本号应自增到 1");
        assertEquals("1", store.get("tcm:ehr:cache:stats:ver:GLOBAL"),
                "全局版本号应自增到 1（report/extra/deduction 等通用 kind 靠它失效）");
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

    // ------------------------------------------------------------ SWR（报告用：秒回旧值 + 后台重算）

    /** 用 trendTruncated 当「本次算的是哪一版」的标记：false/true 代表两次不同结果 */
    private static StatsVO vo(boolean flag) {
        StatsVO v = new StatsVO();
        v.setTrendTruncated(flag);
        return v;
    }

    /** 从键空间里找出报告的数据 key（排除 :fresh 标记），供用例直接摘掉新鲜标记模拟「时间到期」 */
    private String reportDataKey() {
        return store.keySet().stream()
                .filter(k -> k.startsWith("tcm:ehr:cache:stats:report") && !k.endsWith(":fresh"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到报告数据 key：" + store.keySet()));
    }

    @Test
    @DisplayName("SWR：新鲜（data + :fresh 都在）→ 直接命中，不调 loader")
    void swrFreshHitDoesNotReload() {
        AtomicInteger calls = new AtomicInteger();
        cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(false); }, StatsVO.class);
        assertEquals(1, calls.get(), "首次冷算");
        cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(true); }, StatsVO.class);
        assertEquals(1, calls.get(), "新鲜值必须命中，不得再调 loader");
    }

    @Test
    @DisplayName("SWR：陈旧（data 在、:fresh 没了）→ 秒回【旧值】并触发一次后台重算，随后回填新值")
    void swrStaleServesOldValueAndRefreshesOnce() {
        cache.setRefreshExecutorForTest(Runnable::run); // 同步执行器：让后台重算在本次调用内确定性跑完
        AtomicInteger calls = new AtomicInteger();
        cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(false); }, StatsVO.class);
        assertEquals(1, calls.get());

        // 模拟「时间到期」：摘掉新鲜标记（data 仍在 ⇒ 陈旧）
        store.remove(reportDataKey() + ":fresh");
        StatsVO got = cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(true); }, StatsVO.class);
        assertFalse(got.isTrendTruncated(), "陈旧分支必须返回【旧值】，而不是刚重算出的新值");
        assertEquals(2, calls.get(), "陈旧必须触发一次重算");

        // 重算已回填：再读为新鲜，且是新值
        StatsVO again = cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(false); }, StatsVO.class);
        assertTrue(again.isTrendTruncated(), "后台重算应已回填新值");
        assertEquals(2, calls.get(), "回填后应命中，不再重算");
    }

    @Test
    @DisplayName("SWR：升级前遗留的旧值（只有 data、无 :fresh）→ 按陈旧处理：返回旧值 + 刷新，不产生冷算尖峰")
    void swrLegacyValueWithoutFreshMarkerIsTreatedAsStale() {
        cache.setRefreshExecutorForTest(Runnable::run);
        AtomicInteger calls = new AtomicInteger();
        cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(false); }, StatsVO.class);
        // 模拟部署前写入的旧值：data 是合法 VO 的 JSON，但从来没有 :fresh 标记
        store.remove(reportDataKey() + ":fresh");

        StatsVO got = cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(true); }, StatsVO.class);
        assertFalse(got.isTrendTruncated(), "遗留旧值应照常返回（不必让用户等一次冷算）");
        assertEquals(2, calls.get(), "并顺手触发一次后台刷新");
    }

    @Test
    @DisplayName("SWR：写入失效 → 版本段变、两把 key 落空 → 阻塞现算新值（正确性优先）")
    void swrWriteInvalidationBlocksAndRecomputes() {
        cache.setRefreshExecutorForTest(Runnable::run);
        AtomicInteger calls = new AtomicInteger();
        cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(false); }, StatsVO.class);
        assertEquals(1, calls.get());

        cache.invalidateForWrite(); // 任一写入：全局版本号自增 ⇒ data/fresh 都换到新版本段、皆为空
        StatsVO got = cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(true); }, StatsVO.class);
        assertTrue(got.isTrendTruncated(), "写入后首个请求必须拿到【新值】，不能端出旧数字");
        assertEquals(2, calls.get(), "写入后必须阻塞现算，不得走陈旧秒回");
        assertTrue(store.keySet().stream().anyMatch(k -> k.contains(":v1:")),
                "新值应落在新版本段（v1）");
    }

    @Test
    @DisplayName("SWR：值反序列化失败（类结构不兼容）→ 视同未命中，阻塞现算，不把脏值当命中")
    void swrUnparsableValueFallsBackToBlockingCompute() {
        cache.setRefreshExecutorForTest(Runnable::run);
        AtomicInteger calls = new AtomicInteger();
        cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(false); }, StatsVO.class);
        String dataKey = reportDataKey();
        store.put(dataKey, "{不是合法 JSON");  // 脏值
        store.put(dataKey + ":fresh", "1");     // 连标记都在，也不能当命中

        StatsVO got = cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(true); }, StatsVO.class);
        assertTrue(got.isTrendTruncated());
        assertEquals(2, calls.get(), "反序列化失败必须现算");
    }

    @Test
    @DisplayName("SWR：Redis 读故障 → 直接现算返回，不抛异常")
    void swrRedisFailureDegradesToCompute() {
        org.mockito.Mockito.reset(redis);
        when(redis.opsForValue()).thenThrow(new RuntimeException("连接拒绝"));
        AtomicInteger calls = new AtomicInteger();
        StatsVO got = cache.getOrLoadSwr("report", null, null, Duration.ofSeconds(180),
                () -> { calls.incrementAndGet(); return vo(true); }, StatsVO.class);
        assertEquals(1, calls.get(), "Redis 故障时应直接现算");
        assertTrue(got.isTrendTruncated());
    }
}
