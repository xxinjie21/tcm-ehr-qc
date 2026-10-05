package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.domain.dto.DeleteRecordsDTO;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.DeleteRecordsVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 病历删除（批次 13 · 13.3 的第一个抽取对象）。
 *
 * <p><b>为什么先抽它</b>：它是 `RecordServiceImpl` 里最自洽的一块 —— 三个私有方法
 * （{@code filterAccessibleIds} / {@code doDelete} / {@code hasAnyFilter}）与一个常量
 * {@code DELETE_CHUNK} 只服务于删除两条入口（按 ID、按范围），对外零依赖，
 * 且已经有 6 个测试用例兜底（守卫 4 例 + 执行 2 例）。抽它风险最小、也最能验证抽取流程本身。</p>
 *
 * <p><b>为什么 mappers 由调用方传入而不是构造注入</b>：{@code RecordServiceImpl} 继承
 * MyBatis-Plus 的 {@code ServiceImpl}，它的 {@code baseMapper} 是 protected 字段、由框架
 * **构造之后**注入。如果这里构造注入，构造时拿到的会是 null。因此由调用方在**每次调用时**
 * 用 {@code getBaseMapper()} 取当前值传进来 —— 顺带也让现有测试里「反射注入 baseMapper」
 * 的做法继续有效，抽取不会把既有安全网打掉。</p>
 *
 * <p>三条从原代码带过来的规则，务必保持：</p>
 * <ol>
 * <li><b>先删子表再删主表</b>：{@code review_tasks.record_id} 有外键指向 records.id，顺序反了会撞约束；</li>
 * <li><b>按范围删必须有筛选条件</b>：否则就是删全库，直接拒绝；</li>
 * <li><b>请求体里的 id 不可信</b>：按 ID 删只删「按数据域查得到」的那些，查不到的静默剔除。</li>
 * </ol>
 */
@RequiredArgsConstructor
public class RecordDeleter {

    /** 分块大小：一条 IN 塞几万个 id 会让 SQL 慢到超时 */
    static final int DELETE_CHUNK = 500;

    private final RecordMapper baseMapper;
    private final ReviewTaskMapper reviewTaskMapper;

    /**
     * 按 ID 删除：先按数据域过滤请求体里的 id，再交给 {@link #doDelete}。
     *
     * <p>注：事务注解在调用方（{@code RecordServiceImpl.deleteRecords}）的 public 方法上 ——
     * 注解加在私有方法或本类内部自调用上不经过代理，等于没加。</p>
     */
    public DeleteRecordsVO deleteByIds(DeleteRecordsDTO dto) {
        // 1. 没选 id 直接拒：空删会静默「成功」，用户以为删掉了
        if (dto == null || dto.getIds() == null || dto.getIds().isEmpty()) {
            throw new IllegalArgumentException("未选择要操作的病历");
        }
        // 2. 请求体里的 id 不可信：别组的必须剔除
        List<String> accessible = filterAccessibleIds(dto.getIds());
        // 3. 同一段删除逻辑
        return doDelete(accessible);
    }

    /** 按筛选范围删除：只取 id 列，再走同一段删除逻辑 */
    public DeleteRecordsVO deleteByFilter(FiltersDTO filters) {
        // 1. 必须至少有一个筛选条件，否则就是「删全库」
        if (!hasAnyFilter(filters)) {
            throw new IllegalArgumentException("请至少设置一个筛选条件，避免误删全库");
        }
        // 2. 只取 id 列，不取整行数据
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentOrgId(), filters);
        List<Record> rows = baseMapper.selectList(wrapper.select("id"));
        List<String> ids = rows.stream().map(Record::getId).toList();
        // 3. 走同一段删除逻辑
        return doDelete(ids);
    }

    /**
     * 按当前数据域筛出可访问的病历 id，供按 ID 删除做前置过滤。
     *
     * <p>本方法存在的唯一理由：按 ID 删除与按范围删除必须同一口径。按范围删除经
     * {@link RecordFilter#build} 天然带数据域，而按 ID 删除的 id 来自请求体，少了这一步
     * 就能删掉别组病历（连带其 review_tasks）。</p>
     *
     * <p>不可访问的 id 直接剔除、不报错：一条 id 属不属于本组是授权问题，不是业务错误。</p>
     */
    private List<String> filterAccessibleIds(List<String> ids) {
        List<String> accessible = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += DELETE_CHUNK) {
            List<String> chunk = ids.subList(i, Math.min(i + DELETE_CHUNK, ids.size()));
            List<Record> rows = baseMapper.selectList(RecordFilter
                    .build(RequestUtils.currentOrgId(), new FiltersDTO())
                    .select("id")
                    .in("id", chunk));
            for (Record row : rows) {
                accessible.add(row.getId());
            }
        }
        return accessible;
    }

    /** 实际删除：先清外键依赖（review_tasks.record_id → records.id），再分块删病历 */
    private DeleteRecordsVO doDelete(List<String> ids) {
        DeleteRecordsVO vo = new DeleteRecordsVO();
        // 1. 空集合按删 0 条返回
        if (ids == null || ids.isEmpty()) {
            vo.setDeletedCount(0);
            return vo;
        }
        // 2. 分块删：一条 IN 塞几万个 id 会让 SQL 慢到超时
        int deleted = 0;
        for (int i = 0; i < ids.size(); i += DELETE_CHUNK) {
            List<String> chunk = ids.subList(i, Math.min(i + DELETE_CHUNK, ids.size()));
            // 3. 顺序不能反：先删子表再删主表，反了会撞外键
            reviewTaskMapper.delete(new QueryWrapper<com.tcm.ehr.domain.po.ReviewTask>().in("record_id", chunk));
            deleted += baseMapper.deleteBatchIds(chunk);
        }
        vo.setDeletedCount(deleted);
        return vo;
    }

    /** 范围条件是否至少有一个（部门/证候/分级任一非空，或时间区间两端齐全） */
    private boolean hasAnyFilter(FiltersDTO f) {
        // 1. 没有条件对象就没有任何筛选
        if (f == null) {
            return false;
        }
        // 2. 时间区间要两端都有才算一个条件，缺一端会变成「从某时到最新」这种误删口径
        boolean range = f.getDateRange() != null && f.getDateRange().size() == 2
                && !TextUtil.isBlank(f.getDateRange().get(0)) && !TextUtil.isBlank(f.getDateRange().get(1));
        // 3. 任一维度非空即算有筛选
        return !TextUtil.isBlank(f.getDepartment()) || !TextUtil.isBlank(f.getPattern()) || !TextUtil.isBlank(f.getGrade()) || range;
    }
}
