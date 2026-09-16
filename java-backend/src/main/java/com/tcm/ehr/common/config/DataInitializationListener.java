package com.tcm.ehr.common.config;

import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.common.utils.DictionaryStore;
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

import java.io.IOException;
import java.util.List;

/**
 * 启动监听器：
 * 1. 验证Redis/ES连接
 * 2. 加载data/dictionaries/*.json -> 内存缓存 + ES索引重建（术语库"提前内置"）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializationListener implements ApplicationRunner {

    private final StringRedisTemplate redisTemplate;
    private final IDictionaryFileService fileService;
    private final DictionaryStore store;
    private final IEsTermIndexService esTermIndexService;
    private final RestHighLevelClient esClient;

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
        try {
            boolean ping = esClient.ping(RequestOptions.DEFAULT);
            log.info("[ES] 连接成功: ping={}", ping);
        } catch (IOException e) {
            log.warn("[ES] 连接失败: {}", e.getMessage());
        }

        // 3. MySQL数据源
        log.info("[MySQL] 数据源已配置（tcm_ehr@localhost:3306）");

        // 4. 术语库加载（提前内置）：JSON文件 -> 内存缓存 -> ES索引重建
        loadDictionaries();

        log.info("========== 系统启动初始化完成 ==========");
    }

    /**
     * 加载四类词典：读文件、刷内存缓存、重建ES索引
     */
    private void loadDictionaries() {
        for (String type : DictionaryStore.TYPES) {
            try {
                List<TermEntry> entries = fileService.read(type);
                if (entries.isEmpty()) {
                    log.warn("[词典] {} 词典文件为空或不存在，跳过（可到术语词典页导入）", type);
                    continue;
                }
                store.put(type, entries);
                esTermIndexService.rebuild(type, entries);
                log.info("[词典] {} 加载 {} 条术语", type, entries.size());
            } catch (Exception e) {
                log.error("[词典] {} 加载失败: {}", type, e.getMessage());
            }
        }
    }
}
