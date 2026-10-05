package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量解析的「最近任务」列表。
 *
 * <p>为什么不是一个裸数组：服务端只取最近 N 条，裸数组**没法把「你看到的不是全部」告诉页面** ——
 * 于是前端只能自己写死一句「最多显示最近 50 条」当标题：不足 50 条时这句话是错的，
 * 真被截断时也没法给出「更早的任务已省略」这个信息。</p>
 *
 * <p>{@link #truncated} 用「多取一条」精确判定，不是拿 {@code size == limit} 猜 ——
 * 后者会把「恰好 50 条」误报成被截断。</p>
 */
@Data
public class NlpTasksVO {

    /** 最近的任务，按创建时间倒序 */
    private List<NlpTaskVO> tasks = new ArrayList<>();

    /** 是否因超过上限而被截断（true = 还有更早的任务没返回） */
    private boolean truncated;

    /** 本次的上限条数 */
    private int limit;
}
