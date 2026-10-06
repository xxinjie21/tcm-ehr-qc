package com.tcm.ehr.common.config;

import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.common.config.EntityTypes;
import com.tcm.ehr.service.DictionaryTermStore;
import com.tcm.ehr.service.IEsTermIndexService;
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
 * 2. 加载data/dictionaries/*.json -> ES索引重建（术语库"提前内置"）
 *
 * 2026-09-23 起不再往内存里塞一份词典缓存 —— 归一改为只认 ES 索引，
 * 加载只做「文件 -> ES」这一步。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializationListener implements ApplicationRunner {

    private final StringRedisTemplate redisTemplate;
    private final IDictionaryFileService fileService;
    private final IEsTermIndexService esTermIndexService;
    private final com.tcm.ehr.service.DictionaryTermStore termStore;
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
            redisTemplate.opsForValue().set("tcm:startup:ping", "ok", java.time.Duration.ofSeconds(10));
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
     * 词典启动对账：播种基础层 → 逐个 (类型, 组织) 比对版本 → 落后才重建。
     *
     * 为什么先播种：词典源已从 JSON 文件改为 dictionary_terms。若不做这一步，
     * 库里 org_id='' 基础层是空的，词典页会一片空白、归一全部落空 ——
     * 而文件里的词还在，只是没人读它了。判定「尚未播种」用「基础层该类型行数为 0」，
     * 不看版本号：文件里已被人删空的类型也该保持空。
     *
     * 为什么逐组织对账：ES 的 _meta.version 是索引级的，分不清组织。
     * 权威是 dictionary_versions.indexed_version —— 只有它追上 version
     * 才算该组织已同步；灌失败不更新它，下次启动自然还会重建。
     */
    private void loadDictionaries() {
        for (String type : EntityTypes.dictKeys()) {
            try {
                seedBaseIfEmpty(type);
                reconcileOne(type);
            } catch (Exception e) {
                log.error("[词典] {} 启动加载失败（该类术语的归一将不可用）: {}", type, e.getMessage());
            }
        }
    }

    /** 基础层该类型为空且文件里有词时，把文件内容播种进 {@code dictionary_terms} */
    private void seedBaseIfEmpty(String type) {
        if (!termStore.read(DictionaryTermStore.BASE_ORG, type).isEmpty()) {
            return;
        }
        List<TermEntry> fromFile;
        try {
            fromFile = fileService.read(type);
        } catch (Exception e) {
            log.warn("[词典] {} 读取词典文件失败，跳过播种: {}", type, e.getMessage());
            return;
        }
        if (fromFile.isEmpty()) {
            return;
        }
        String version = termStore.replace(DictionaryTermStore.BASE_ORG, type, fromFile);
        log.info("[词典] {} 基础层播种 {} 条（来源：词典文件，版本 {}）", type, fromFile.size(), version);
    }

    /** 基础层 + 所有有词条的组织，逐个比对版本对账 */
    private void reconcileOne(String type) throws java.io.IOException {
        reconcileLayer(type, DictionaryTermStore.BASE_ORG);
        for (String orgId : orgsWithTerms(type)) {
            if (!DictionaryTermStore.BASE_ORG.equals(orgId)) {
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
        // 这里按「类型+组织」加 DB 命名锁；拿不到锁就让本次跳过（下一实例或下次
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
