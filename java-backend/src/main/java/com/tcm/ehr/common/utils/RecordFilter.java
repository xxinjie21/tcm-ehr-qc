package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;

/**
 * 病历公共过滤（批F·7.4）：**数据域（行级权限）→ 用户筛选** 顺序固定、取交集。
 *
 * <p>所有病历读取（查询 / 原始查看 / 后续图谱等）统一走本工具，禁止在业务方法中手写 where，
 * 避免口径漂移与"筛选条件绕过数据域"的越权。</p>
 *
 * <ul>
 *   <li>管理员 = 全库；</li>
 *   <li>审核员 = 复核域（{@code grade='待复核'}），再叠加用户筛选。</li>
 * </ul>
 */
public final class RecordFilter {

    public static final String ROLE_ADMIN = "管理员";
    public static final String ROLE_AUDITOR = "审核员";

    private RecordFilter() {
    }

    /** 构建查询条件：先数据域、后用户筛选 */
    public static QueryWrapper<Record> build(String role, SearchDTO dto) {
        QueryWrapper<Record> wrapper = new QueryWrapper<>();
        // ① 数据域（行级权限）——必须先于用户筛选
        operatorScope(wrapper, role);
        // ② 用户筛选
        if (dto != null) {
            if (notBlank(dto.getRegistrationNo())) {
                wrapper.like("registration_no", dto.getRegistrationNo().trim());
            }
            if (notBlank(dto.getOutpatientNo())) {
                wrapper.like("outpatient_no", dto.getOutpatientNo().trim());
            }
            if (notBlank(dto.getGender())) {
                wrapper.eq("gender", dto.getGender().trim());
            }
            if (notBlank(dto.getDepartment())) {
                wrapper.eq("department", dto.getDepartment().trim());
            }
            if (notBlank(dto.getDoctorId())) {
                wrapper.eq("doctor_id", dto.getDoctorId().trim());
            }
            if (notBlank(dto.getPattern())) {
                wrapper.like("pattern", dto.getPattern().trim());
            }
            if (notBlank(dto.getGrade())) {
                wrapper.eq("grade", dto.getGrade().trim());
            }
            if (notBlank(dto.getStatus())) {
                wrapper.eq("status", dto.getStatus().trim());
            }
            if (dto.getDateRange() != null && dto.getDateRange().size() == 2
                    && notBlank(dto.getDateRange().get(0)) && notBlank(dto.getDateRange().get(1))) {
                wrapper.ge("visit_time", dto.getDateRange().get(0).trim() + " 00:00:00");
                wrapper.le("visit_time", dto.getDateRange().get(1).trim() + " 23:59:59");
            }
        }
        wrapper.orderByDesc("create_time");
        return wrapper;
    }

    /** 数据域（行级权限）：审核员强制待复核域；管理员全库 */
    private static void operatorScope(QueryWrapper<Record> wrapper, String role) {
        if (ROLE_AUDITOR.equals(role)) {
            wrapper.eq("grade", "待复核");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
