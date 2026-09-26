package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.LlmConfigDTO;
import com.tcm.ehr.domain.vo.LlmConfigVO;
import com.tcm.ehr.domain.vo.LlmTestVO;

/**
 * LLM 运行时配置：读取、保存、连通性探测。
 *
 * <p>配置只在内存与配置文件之间合并，不写回 application.yml。</p>
 */
public interface ILlmConfigService {

    /**
     * 读取当前生效配置。
     *
     * @return enabled/provider/model 等；密钥只回掩码
     */
    LlmConfigVO get();

    /**
     * 覆盖运行时配置并立即生效。
     *
     * @param dto 连接参数，空值沿用当前值
     * @return 生效后的配置
     */
    LlmConfigVO update(LlmConfigDTO dto);

    /**
     * 探测连通性，不改变生效配置。
     *
     * @param dto 待试参数，为 {@code null} 表示用当前生效配置
     * @return ok=是否连通；provider/model/latencyMs=探测结果
     * @throws IllegalArgumentException 通道或密钥不合法
     * @throws IllegalStateException    通信或鉴权失败，消息已脱敏
     */
    LlmTestVO test(LlmConfigDTO dto);
}
