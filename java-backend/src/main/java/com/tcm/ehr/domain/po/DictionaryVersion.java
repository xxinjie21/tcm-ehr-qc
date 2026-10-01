package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 组织级词典索引版本（{@code dictionary_versions}，主键 {@code (org_id, type)}）。
 *
 * <p><b>这是「索引里灌的到底是哪一版」的唯一权威</b>：ES 上的 {@code _meta.version} 只是
 * 索引级的一个标，无法区分组织。ES 挂了不算「索引好了」—— 只有 {@code indexedVersion}
 * 追上 {@code version}，才算该组织的词全部进了 ES。</p>
 *
 * <p>「基础层」也记一行（{@code orgId} 为空串）：否则基础层改了，别的组织行还是旧版本号，
 * 启动时误判「已同步」而跳过重建 → 归一用到过期基础层。</p>
 */
@Data
@TableName("dictionary_versions")
public class DictionaryVersion {

    /** 所属组织；{@code ""} = 基础层（共享）。与 type 组成联合主键，无独立 id 列。 */
    private String orgId;

    /** 术语类型 */
    private String type;

    /** 词条内容版本（内容不变则稳定，用于判断 DB 内容是否变化） */
    private String version;

    /** 已灌入 ES 的版本（内容版本 + 索引结构版本合成）；落后于 version 即待重建 */
    private String indexedVersion;

    /** 实际灌入 ES 的时间；null = 从没成功灌过 */
    private java.time.LocalDateTime indexedAt;
}
