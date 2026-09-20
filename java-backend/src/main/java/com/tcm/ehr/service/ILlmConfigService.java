package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.LlmConfigDTO;
import com.tcm.ehr.domain.vo.LlmConfigVO;
import com.tcm.ehr.domain.vo.LlmTestVO;

/**
 * LLM 运行时配置服务（UX-68）：读取 / 覆盖 / 连通性探测。
 *
 * <p>覆盖只作用于内存中的运行时层，<b>不写回 {@code application.yml}</b>，重启后回到配置文件基线。</p>
 */
public interface ILlmConfigService {

    /** 当前生效配置（密钥以掩码回传） */
    LlmConfigVO get();

    /** 覆盖运行时配置并立即生效；返回生效后的配置 */
    LlmConfigVO update(LlmConfigDTO dto);

    /**
     * 用给定参数做一次连通性探测，<b>不落库、不生效</b>（先试后存）。
     *
     * @param dto 待探测参数；null 表示探测当前生效配置
     * @return 探测结果
     * @throws IllegalArgumentException 参数非法（通道非法 / openai 缺 api-key）
     * @throws IllegalStateException    连接或鉴权失败（消息已脱敏，可直接展示）
     */
    LlmTestVO test(LlmConfigDTO dto);
}
