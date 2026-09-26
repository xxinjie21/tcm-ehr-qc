package com.tcm.ehr.common.config;

import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.common.utils.TermTypes;
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
 * <p>2026-09-23 起不再往内存里塞一份词典缓存 —— 归一改为只认 ES 索引，
 * 加载只做「文件 -> ES」这一步。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializationListener implements ApplicationRunner {

    private final StringRedisTemplate redisTemplate;
    private final IDictionaryFileService fileService;
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
     * 加载五类词典：读文件、重建 ES 索引。
     *
     * <p>ES 重建失败只记日志、不让启动失败 —— 但要注意此时归一整体不可用（会返回 503），
     * 日志里必须能看出是哪一类、什么原因。</p>
     */
    private void loadDictionaries() {
        // 词典版本只算一次：它对 5 类词典是同一个值（各文件都参与哈希）
        String version = fileService.currentVersion();
        for (String type : TermTypes.ALL) {
            try {
                List<TermEntry> entries = fileService.read(type);
                if (entries.isEmpty()) {
                    log.warn("[词典] {} 词典文件为空或不存在，跳过（可到术语词典页导入）", type);
                    continue;
                }
                // 索引已是这个版本就跳过重建。rebuild 是 delete+create，中间有个窗口，
                // 期内归一检索会拿 index_not_found → 归一接口回 503（表现为「重启后第一次解析偶发失败」）。
                // 词典没变却每次重启都重建 5 次，纯属白付这个代价。
                if (esTermIndexService.exists(type) && version.equals(esTermIndexService.indexedVersion(type))) {
                    log.info("[词典] {} 索引已是最新（{}），跳过重建", type, version);
                    continue;
                }
                esTermIndexService.rebuild(type, entries, version);
                log.info("[词典] {} 加载 {} 条术语", type, entries.size());
            } catch (Exception e) {
                log.error("[词典] {} 加载失败（该类术语的归一将不可用）: {}", type, e.getMessage());
            }
        }
    }
}
