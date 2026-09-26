package com.tcm.ehr.common.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端配置：术语归一的词典索引检索走这条连接。
 */
@Configuration
public class EsConfig {

    /** ES 主机，默认本机 */
    @Value("${elasticsearch.host:localhost}")
    private String host;

    /** ES 端口，默认 9200 */
    @Value("${elasticsearch.port:9200}")
    private int port;

    /** 连接协议，默认 http */
    @Value("${elasticsearch.scheme:http}")
    private String scheme;

    /**
     * 构建 ES 高级客户端，容器关闭时自动 close。
     *
     * @return 全局共用的 RestHighLevelClient
     */
    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient() {
        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, scheme));
        // 1. 连接 5s 超时：ES 不可用要快速暴露，归一接口据此回 503
        //    读写 30s 超时：容纳词典索引重建时的批量写入
        builder.setRequestConfigCallback(config -> config
                .setConnectTimeout(5000)
                .setSocketTimeout(30000));
        return new RestHighLevelClient(builder);
    }
}
