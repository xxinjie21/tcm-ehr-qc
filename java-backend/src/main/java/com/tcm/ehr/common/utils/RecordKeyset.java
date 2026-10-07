package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.po.Record;

import java.time.LocalDateTime;

/**
 * 病历批处理扫描的 keyset（游标）分页条件 —— 唯一实现，两处服务复用。
 *
 * <p>为什么需要 keyset（性能审查 P0-2）：offset 分页每页「{@code ORDER BY ...
 * LIMIT offset, 1000}」都要把**剩余全表**重排一遍，扫 4 万行 ≈ 41 页 = 41 次 filesort；
 * keyset 锚定上一页末条的 {@code (visit_time, id)} 后只做区间扫描（配
 * {@code idx_records_org_vt_id} 后实测 4.9s → 0.2s）。</p>
 *
 * <p><b>严格全序前提</b>：{@code id} 是唯一主键 → 组合键 {@code (visit_time, id)}
 * 唯一，游标既不会漏行也不会重复。锚点取「上一页**末条**」且必含 {@code id}
 * （同 {@code visit_time} 有多条时只比 {@code visit_time} 会漏）。</p>
 *
 * <p>注意：{@code visit_time} 须非空（与既有排序 / 分页口径一致）；MySQL 对
 * {@code NULL} 比较是 {@code UNKNOWN}，若数据存在空接诊时间需先补数。</p>
 */
public final class RecordKeyset {

    private RecordKeyset() {
    }

    /**
     * 在分页 wrapper 上追加 keyset 条件：取严格全序 {@code (visit_time DESC, id ASC)}
     * 中「锚点 {@code (lastVt, lastId)} 之后」的行。
     *
     * @param w       分页 wrapper（调用方已带数据域筛选与 ORDER BY）
     * @param lastVt  上一页末条的 visit_time
     * @param lastId  上一页末条的 id（必传，防止同 visit_time 边界漏/重）
     */
    public static void anchorAfter(QueryWrapper<Record> w, LocalDateTime lastVt, String lastId) {
        w.and(q -> q.lt("visit_time", lastVt)
                .or(p -> p.eq("visit_time", lastVt).gt("id", lastId)));
    }
}