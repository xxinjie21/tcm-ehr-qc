package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.domain.po.OrganizationMember;
import com.tcm.ehr.domain.po.OperationLog;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.mapper.OrgMapper;
import com.tcm.ehr.mapper.OperationLogMapper;
import com.tcm.ehr.service.ILogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志审计实现：读 operation_log 做分页筛选与导出。
 * §七 L2/L3：归档文件与 purge 均已删，现在只剩「读库 + 导 CSV」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogServiceImpl implements ILogService {

    /** 页面展示用的时间格式 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 本实体的 Mapper，统一命名为 baseMapper（附录 A.1 #21） */
    private final OperationLogMapper baseMapper;
    private final OrgMapper orgMapper;

    /**
     * 分页查询操作日志。
     *
     * @param action 操作类型，为空表示不限
     * @param keyword 关键字，匹配操作人 / 操作对象 / 详情
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return total=总条数、list=当前页记录
     */
    @Override
    public Map<String, Object> page(String action, String keyword, int page, int size) {
        // 1. 分页参数兜底为 1（非法分页会让 SQL 报错），条件走统一 wrapper
        Page<OperationLog> p = baseMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.max(size, 1)), buildWrapper(action, keyword));
        // 2. 固定顺序装 total / list，前端按 key 取
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", p.getTotal());
        // 「所属组」列展示用：库里只有 org_id，这里解析成组织名
        fillOrgNames(p.getRecords());
        result.put("list", p.getRecords());
        return result;
    }

    /**
     * 取全部操作类型，供筛选下拉框使用。
     *
     * @return 去重后的操作类型列表
     */
    @Override
    public List<String> actions() {
        // 成员不能从下拉看到别人的操作类型。
        // 三档可见范围见 buildWrapper；这里要单独传给硬编码 SQL（不能复用 wrapper）。
        String orgId = RequestUtils.currentOrgId();
        String operator = RequestUtils.currentUsername();
        if (RequestUtils.isAdmin()) {
            return baseMapper.selectDistinctActions(null, null);
        }
        if (orgId != null && !orgId.isBlank()
                && RequestUtils.currentOrgRole().equals(OrganizationMember.ROLE_OWNER)) {
            return baseMapper.selectDistinctActions(orgId, null);
        }
        return baseMapper.selectDistinctActions(orgId, operator);
    }

    /**
     * 按条件取全部日志（不分页），供导出使用。
     *
     * @param action 操作类型，为空表示不限
     * @param keyword 关键字，匹配操作人 / 操作对象 / 详情
     * @return 命中条件的日志列表
     */
    @Override
    public List<OperationLog> listForExport(String action, String keyword) {
        List<OperationLog> rows = baseMapper.selectList(buildWrapper(action, keyword));
        fillOrgNames(rows);
        return rows;
    }

    /**
     * 导出日志为 CSV 字节流。
     *
     * @param action 操作类型，为空表示不限
     * @param keyword 关键字，匹配操作人 / 操作对象 / 详情
     * @return 带 UTF-8 BOM 的 CSV 内容
     */
    @Override
    public byte[] exportCsv(String action, String keyword) {
        return csvBytes(listForExport(action, keyword));
    }

    /**
     * 取某操作人最近的若干条日志，供 AI 助手理解上下文。
     *
     * @param operator 操作人
     * @param limit 最多返回条数
     * @return 按时间倒序的日志列表；入参非法时返回空列表
     */
    @Override
    public List<OperationLog> listRecentByOperator(String operator, int limit) {
        // 1. 入参缺失直接返回空，避免拼出无意义的查询
        if (operator == null || operator.isBlank() || limit <= 0) {
            return List.of();
        }
        // 2. 按时间倒序取前 limit 条
        QueryWrapper<OperationLog> w = new QueryWrapper<OperationLog>()
                .eq("operator", operator)
                .orderByDesc("log_time")
                .last("LIMIT " + limit);
        return baseMapper.selectList(w);
    }

    /** 生成带 UTF-8 BOM 的 CSV 字节（Excel 正确识别中文） */
    private byte[] csvBytes(List<OperationLog> rows) {
        // 1. 写表头
        StringBuilder sb = new StringBuilder();
        sb.append("操作时间,操作人,角色,所属组织,操作类型,操作对象,详情\n");
        // 2. 逐条拼行（字段值按 CSV 规则转义）
        for (OperationLog l : rows) {
            sb.append(csv(l.getLogTime() == null ? "" : l.getLogTime().format(TS))).append(',')
                    .append(csv(l.getOperator())).append(',')
                    .append(csv(l.getRole())).append(',')
                    .append(csv(l.getOrgName())).append(',')
                    .append(csv(l.getAction())).append(',')
                    .append(csv(l.getTarget())).append(',')
                    .append(csv(l.getDetail())).append('\n');
        }
        // 3. 前置 UTF-8 BOM 后返回
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    /**
     * 批量把 {@code orgId} 解析成组织名回填到 {@code orgName}。
     *
     * <p><b>一次查询回填整页</b>：逐行查组织名会让「每页 20 条」变成 20 次查询，
     * 日志页是高频访问路径。这里先去重再一次性 {@code IN} 查询。</p>
     *
     * <p>解析不到（组织已删 / 该行为历史数据无归属）时置「—」而不是留空：
     * 空字符串会让人以为是「查到了但没值」。</p>
     */
    private void fillOrgNames(List<OperationLog> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 1. 去重收集本页出现过的组织 ID
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (OperationLog l : rows) {
            if (l.getOrgId() != null && !l.getOrgId().isBlank()) {
                ids.add(l.getOrgId());
            }
        }
        if (ids.isEmpty()) {
            for (OperationLog l : rows) {
                l.setOrgName("—");
            }
            return;
        }
        // 2. 一次性取回 id -> 名称
        Map<String, String> nameById = new HashMap<>();
        for (com.tcm.ehr.domain.po.Organization o
                : orgMapper.selectBatchIds(ids)) {
            nameById.put(o.getId(), o.getName());
        }
        // 3. 回填；解析不到给「—」
        for (OperationLog l : rows) {
            String name = l.getOrgId() == null ? null : nameById.get(l.getOrgId());
            l.setOrgName(name == null || name.isBlank() ? "—" : name);
        }
    }

    /** 组装筛选条件：操作类型精确匹配 + 关键字模糊匹配 + §七 L7 的三档可见性范围 */
    private QueryWrapper<OperationLog> buildWrapper(String action, String keyword) {
        QueryWrapper<OperationLog> w = new QueryWrapper<>();
        // 1. §七 L7 四档范围：管理员全部 / 组长本组 /
        //    成员本组织自己 / 无组织自己（一次覆盖 page、listForExport、exportCsv）
        String orgId = RequestUtils.currentOrgId();
        String operator = RequestUtils.currentUsername();
        if (RequestUtils.isAdmin()) {
            // 管理员：无条件（看全部）
        } else if (orgId != null && !orgId.isBlank()) {
            w.eq("org_id", orgId);
            if (OrganizationMember.ROLE_MEMBER.equals(RequestUtils.currentOrgRole())) {
                // 成员：只看自己在本组织内的操作
                w.eq("operator", operator);
            }
            // 所有者：本组织全员操作
        } else {
            // 无组织：只能看自己
            w.eq("operator", operator);
        }
        // 2. 操作类型精确匹配
        if (action != null && !action.isBlank()) {
            w.eq("action", action.trim());
        }
        // 3. 关键字三列任一命中
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            w.and(q -> q.like("operator", k).or().like("target", k).or().like("detail", k));
        }
        // 4. 时间倒序
        w.orderByDesc("log_time");
        return w;
    }

    /** CSV 字段转义：含逗号 / 引号 / 换行时用引号包裹，内部引号翻倍 */
    private String csv(String s) {
        // 1. null 给空串（CSV 里空字段就是空）
        if (s == null) {
            return "";
        }
        // 2. 内部引号翻倍
        String v = s.replace("\"", "\"\"");
        // 3. 含分隔符或换行才加引号包裹
        if (v.contains(",") || v.contains("\n") || v.contains("\"")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}
