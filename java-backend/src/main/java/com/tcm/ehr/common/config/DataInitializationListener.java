package com.tcm.ehr.common.config;

import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.common.exception.ServiceNotReadyException;
import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.common.config.EntityTypes;
import com.tcm.ehr.service.IDictionaryTermStore;
import com.tcm.ehr.service.IEsTermIndexService;
import com.tcm.ehr.domain.po.DictionaryVersion;
import com.tcm.ehr.domain.po.TermEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动监听器：
 * 1. 验证Redis/ES连接
 * 2. 加载 {@code classpath:dictionaries/*.json} -> 基础层({@code dictionary_terms}) -> ES 索引重建
 *
 * <p>2026-09-23 起不再往内存里塞一份词典缓存 —— 归一改为只认 ES 索引，
 * 加载只做「文件 -> 库 -> ES」这几步。</p>
 *
 * <p>2026-10-15 起基础层<b>以文件为准</b>：每次启动都比对文件内容指纹与库内基础层版本，
 * 不一致就整层重播。故改完 {@code resources/dictionaries/*.json} <b>重启即生效</b>，
 * 不再需要手工清库。组织层（{@code org_id <> ''}）不受影响。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializationListener implements ApplicationRunner {

    private final StringRedisTemplate redisTemplate;
    private final IDictionaryFileService fileService;
    private final IEsTermIndexService esTermIndexService;
    private final com.tcm.ehr.service.IDictionaryTermStore termStore;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final com.tcm.ehr.common.utils.DistLock distLock;
    private final RestHighLevelClient esClient;

    /**
     * 启动后依次做四件事：探 Redis、探 ES、打印数据源、把词典 JSON 重建进 ES 索引。
     *
     * 探活失败一律只记日志、不让启动失败 —— ES / Redis 不可用时系统仍能起来，
     * 由归一相关接口返回 503 说明原因（见类注释）。
     *
     * @param args 启动参数，此处未使用
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("========== 系统启动初始化开始 ==========");

        // 1. 验证Redis连接
        try {
            redisTemplate.opsForValue().set(
                    com.tcm.ehr.common.utils.RedisKeys.PREFIX + "startup:ping", "ok",
                    java.time.Duration.ofSeconds(10));
            log.info("[Redis] 连接成功: ping/pong ok");
        } catch (Exception e) {
            log.warn("[Redis] 连接失败: {}", e.getMessage());
        }

        // 2. 验证ES连接
        //    必须 catch Exception，不能只 catch IOException：ES 连不上时 ping 抛的是
        //    ElasticsearchException（unchecked，内部包着 ExecutionException / ConnectException），
        //    只抓 IOException 会让它一路冒到 SpringApplication，把整个启动炸掉（实测过）。
        //    ES 不可用应当是「归一不可用」（接口返回 503 说明原因），而不是「系统起不来」。
        try {
            boolean ping = esClient.ping(RequestOptions.DEFAULT);
            log.info("[ES] 连接成功: ping={}", ping);
        } catch (Exception e) {
            log.warn("[ES] 连接失败: {} —— 术语索引不可用，归一相关接口将返回 503", e.getMessage());
        }

        // 3. MySQL数据源
        log.info("[MySQL] 数据源已配置（tcm_ehr@localhost:3306）");

        // 4. 术语库加载（提前内置）：JSON文件 -> ES索引重建
        loadDictionaries();

        log.info("========== 系统启动初始化完成 ==========");
    }

    /**
     * 词典启动对账：基础层与文件对齐 → 逐个 (类型, 组织) 比对版本 → 落后才重建。
     *
     * 为什么先对基础层：词典的实际读取源是 dictionary_terms，ES 也从它重建。
     * 基础层若与文件不一致（文件改过、或库里被清空），词典页会与文件对不上、
     * 归一也会用旧词。故每轮启动先比对文件指纹（见 {@link #syncBaseFromFile}）。
     *
     * 为什么逐组织对账：ES 的 _meta.version 是索引级的，分不清组织。
     * 权威是 dictionary_versions.indexed_version —— 只有它追上 version
     * 才算该组织已同步；灌失败不更新它，下次启动自然还会重建。
     */
    private void loadDictionaries() {
        for (String type : EntityTypes.dictKeys()) {
            try {
                syncBaseFromFile(type);
                reconcileOne(type);
            } catch (Exception e) {
                log.error("[词典] {} 启动加载失败（该类术语的归一将不可用）: {}", type, e.getMessage());
            }
        }
    }

    /**
     * 基础层与词典文件对账：文件内容指纹与库内基础层版本不一致时整层重播。
     *
     * <p><b>为什么不再「只播种空基础层」</b>：原实现只在基础层为空时读文件，于是
     * {@code resources/dictionaries/*.json} 改完后重启<b>不生效</b> —— 基础层非空就早退，
     * 而 ES 又是从库里的旧内容重建的，文件改动永远进不来。现改为每轮启动都拿文件指纹
     * 与 {@code dictionary_versions} 里的基础层内容版本比对：不一致才
     * {@code replace}（先删后插），随后 {@link #reconcileLayer} 顺理成章地重建 ES；
     * 一致则直接返回，不产生任何写操作（绝大多数启动走这条）。</p>
     *
     * <p><b>语义</b>：基础层以文件为准。经应用（导入 / 归档回滚）改过的基础层会在下次启动
     * 被文件覆盖；组织层（{@code org_id <> ''}）不受影响。文件为空视为「不播种」而非
     * 「清空」，避免误删。</p>
     *
     * <p><b>为什么必须加分布式锁（B2）</b>：{@code replace} 是「先删后插」。多实例同时启动时
     * 两个实例若都判定「需重播」会交错删/插，其中一个撞 {@code uk_org_type_term} 抛异常 ⇒
     * 该类型整轮被 {@link #loadDictionaries} 的外层 try 跳过，归一不可用。</p>
     */
    private void syncBaseFromFile(String type) {
        // 1. 锁外先读文件、算指纹：绝大多数启动文件没变，走下面这条直接返回，不必抢锁
        List<TermEntry> fromFile;
        try {
            fromFile = fileService.read(type);
        } catch (Exception e) {
            log.warn("[词典] {} 读取词典文件失败，跳过播种（保留库内现状）: {}", type, e.getMessage());
            return;
        }
        if (fromFile.isEmpty()) {
            log.warn("[词典] {} 词典文件为空，跳过播种（不据此清空库内基础层）", type);
            return;
        }
        String fileVersion = termStore.contentVersion(fromFile);
        DictionaryVersion stored = termStore.findVersion(IDictionaryTermStore.BASE_ORG, type);
        if (stored != null && fileVersion.equals(stored.getVersion())) {
            return; // 文件与库内基础层一致，无需动
        }
        // 2. 抢锁后复查：等锁期间别的实例可能已同步过。版本号用长度 1 的数组带出锁外再打日志
        String[] version = new String[1];
        try {
            distLock.runLocked(
                    com.tcm.ehr.common.utils.DistLock.dictRebuildLock(type, IDictionaryTermStore.BASE_ORG),
                    () -> {
                        DictionaryVersion v = termStore.findVersion(IDictionaryTermStore.BASE_ORG, type);
                        if (v == null || !fileVersion.equals(v.getVersion())) {
                            version[0] = termStore.replace(IDictionaryTermStore.BASE_ORG, type, fromFile);
                        }
                        return null;
                    });
        } catch (ConcurrentOperationException e) {
            log.info("[词典] {} 基础层播种被其它实例占用，本次跳过: {}", type, e.getMessage());
            return;
        } catch (ServiceNotReadyException e) {
            log.warn("[词典] {} 基础层播种跳过：锁服务不可用 -> {}", type, e.getMessage());
            return;
        }
        if (version[0] != null) {
            log.info("[词典] {} 基础层按词典文件重播 {} 条（版本 {}）", type, fromFile.size(), version[0]);
        }
    }

    /** 基础层 + 所有有词条的组织，逐个比对版本对账 */
    private void reconcileOne(String type) throws java.io.IOException {
        reconcileLayer(type, IDictionaryTermStore.BASE_ORG);
        for (String orgId : orgsWithTerms(type)) {
            if (!IDictionaryTermStore.BASE_ORG.equals(orgId)) {
                reconcileLayer(type, orgId);
            }
        }
    }

    /** 单层对账：已同步且索引结构兼容才跳过（rebuild 是先删后灌，期内归一会 503） */
    private void reconcileLayer(String type, String orgId) throws java.io.IOException {
        // 内容版本同步 ≠ 索引结构正确：从旧版本升级时索引可能是旧 mapping
        // （org_id 被动态映射成 text，空串基础层查不到 → 全库「未收录」）。
        // 只信 dictionary_versions 会跳过重建，坏索引就永远修不好。
        if (termStore.isSynced(orgId, type) && esTermIndexService.schemaCompatible(type)) {
            return;
        }
        List<TermEntry> entries = termStore.read(orgId, type);
        if (entries.isEmpty()) {
            return;
        }
        String version = termStore.contentVersion(entries);
        // 跨实例互斥（批次16）：多实例同时启动时，两个实例会同时判定「待重建」并
        // 同时删/建同一索引，交错后索引内容可能是混合状态。
        // 这里按「类型+组织」加 Redisson 分布式锁（DistLock）；拿不到锁就让本次跳过（下一实例或下次
        // 启动会补上）—— 启动期抢不到锁不是错误，不该让启动失败。
        try {
            distLock.runLocked(com.tcm.ehr.common.utils.DistLock.dictRebuildLock(type, orgId),
                    () -> {
                        try {
                            esTermIndexService.rebuild(type, orgId, entries, version);
                        } catch (java.io.IOException io) {
                            throw new java.io.UncheckedIOException(io);
                        }
                        return null;
                    });
        } catch (ConcurrentOperationException e) {
            log.info("[词典] {} (org='{}') 重建被其它实例占用，本次跳过: {}", type, orgId, e.getMessage());
            return;
        } catch (ServiceNotReadyException e) {
            // 启动期 Redis 不可用：与「抢不到锁」同口径 —— 跳过重建而不是让应用起不来。
            // 这是 fail-closed 的唯一例外场景，但它同样没有在无锁状态下写索引，
            // 剩下的实例或下次启动会补上。
            log.warn("[词典] {} (org='{}') 重建跳过：锁服务不可用 -> {}",
                    type, orgId, e.getMessage());
            return;
        }
        // 只有真灌成功才记已同步
        termStore.markIndexed(orgId, type, version);
        log.info("[词典] {} (org='{}') 重建索引，{} 条，版本 {}", type, orgId, entries.size(), version);
    }

    /** 查有哪些组织在该类型下有词条（去重、排除基础层） */
    private List<String> orgsWithTerms(String type) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT DISTINCT org_id FROM dictionary_terms WHERE type = ? AND org_id <> ''",
                    String.class, type);
        } catch (Exception e) {
            log.warn("[词典] {} 列出组织失败（这些组织本轮不重建）: {}", type, e.getMessage());
            return List.of();
        }
    }
}
