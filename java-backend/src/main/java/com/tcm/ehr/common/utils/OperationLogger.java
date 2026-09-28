package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.OperationLog;
import com.tcm.ehr.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 操作日志（文档必做2）：关键操作（清洗 / 导出 / 词典导入 / 词典回滚 / 人工复核 / 批量重算）
 * <b>只入库</b>，写 {@code operation_log} 表，是审计页 {@code GET /api/logs} 的唯一数据源。
 *
 * <p>§七 L2 起不再落文件：原「文件 + 库」双写已删，配置项 {@code log.operation-file}
 * 与 {@code logs/operation.log} 一并作废（{@code logs/} 目录此后后端零引用，
 * {@code .gitignore} 里的 {@code logs/} 保留无害）。</p>
 *
 * <p>§七 L1 起不记 IP（PIPL 最小必要）。</p>
 *
 * <p><b>已知代价</b>（用户已同意）：入库失败时没有文件兜底，仅打 WARN。该条操作在审计页缺失。</p>
 *
 * <p>操作人 / 角色取自 JwtInterceptor 写入的 request 属性（见 {@link RequestUtils}）。
 * ⚠️ 后台线程（异步任务）读不到线程绑定的 request 属性，会取到 {@code "unknown"}；
 * 异步任务须用 {@link #log(String, String, String, String, String)} 显式传提交时捕获的操作人与角色。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationLogger {

    /** 库字段长度上限（与 database-init.sql 对齐），超长截断，避免插入失败丢整条 */
    private static final int MAX_OPERATOR = 50;
    private static final int MAX_ROLE = 20;
    private static final int MAX_ACTION = 50;
    private static final int MAX_TARGET = 255;

    private final OperationLogMapper operationLogMapper;

    /**
     * 记录一条关键操作
     *
     * @param action 操作类型（数据清洗 / 数据集导出 / 词典导入 / 词典回滚 / 人工复核 / 批量重算）
     * @param target 操作对象（筛选范围 / 文件名 / 词典类型 / 病历ID），可为 null
     * @param detail 操作明细，可为 null
     */
    public void log(String action, String target, String detail) {
        // 1. 从当前请求取操作人上下文（脱离 Web 请求时各字段为 "unknown"）
        log(action, target, detail, RequestUtils.currentUsername(), RequestUtils.currentRole());
    }

    /**
     * 记录一条关键操作，<b>操作人与角色由调用方指定</b>。
     *
     * <p>供后台线程（批量重算 / 批量解析等异步任务）使用：这些线程没有
     * {@code RequestContextHolder} 上下文，{@link #log(String, String, String)} 会把操作人
     * 记成 {@code "unknown"}。异步任务应在<b>提交线程</b>捕获操作人与角色，
     * 由 worker 线程回填 —— 与 {@code qc_task.role} 快照是同一个理由。</p>
     *
     * @param action   操作类型
     * @param target   操作对象，可为 null
     * @param detail   操作明细，可为 null
     * @param operator 操作人用户名
     * @param role     操作人角色
     */
    public void log(String action, String target, String detail, String operator, String role) {
        // 同步任务（如导入 / 清洗）由本重载走请求线程，组从 RequestUtils 取；
        // 异步任务（批量重算）用 6 参重载显式传组快照
        groupLog(action, target, detail, operator, role, RequestUtils.currentGroupId());
    }

    /**
     * 记录一条关键操作，<b>操作人 / 角色 / 组由调用方指定</b>。
     *
     * <p>异步任务的收尾日志必须用它：worker 线程既拿不到 operator / role，
     * 也拿不到 groupId（{@code RequestContextHolder} 不在该线程上）。
     * groupId 若在提交线程即时为空（待分配池用户提交），这里应传空串，
     * 落库的 {@code group_id} 即为 NULL —— 该操作永远只对操作人本人可见。</p>
     *
     * @param action   操作类型
     * @param target   操作对象，可为 null
     * @param detail   操作明细，可为 null
     * @param operator 操作人用户名
     * @param role     操作人角色
     * @param groupId  操作时所属组（可为空串，等价于无组）
     */
    public void log(String action, String target, String detail, String operator, String role, String groupId) {
        groupLog(action, target, detail, operator, role, groupId);
    }

    private void groupLog(String action, String target, String detail, String operator, String role, String groupId) {
        // 截断到秒：库列 DATETIME(0) 对小数秒是四舍五入，不截断会让同一操作在不同出口相差 1 秒
        LocalDateTime now = LocalDateTime.now().withNano(0);
        insertDb(now, operator, role, action, target, detail,
                groupId == null || groupId.isBlank() ? null : groupId.trim());
    }

    /** 入库（审计页唯一数据源）；失败仅告警，不阻塞业务 */
    private void insertDb(LocalDateTime now, String operator, String role,
                          String action, String target, String detail, String groupId) {
        try {
            // 1. 各字段按列宽截断：超长会撞库列长度限制
            OperationLog row = new OperationLog();
            row.setLogTime(now);
            row.setOperator(cut(operator, MAX_OPERATOR));
            row.setRole(cut(role, MAX_ROLE));
            row.setAction(cut(action, MAX_ACTION));
            row.setTarget(cut(target, MAX_TARGET));
            row.setGroupId(groupId);
            // 2. 明细列不截断（TEXT 列），只去首尾空白
            row.setDetail(detail == null ? null : detail.trim());
            operationLogMapper.insert(row);
        } catch (Exception e) {
            // 3. 入库失败只告警，不该因此中断业务（代价：审计页缺该条，用户已知悉）
            log.warn("[操作日志] 入库失败（不影响业务）: {}", e.getMessage());
        }
    }

    private static String cut(String s, int max) {
        // 1. null 保持 null（区分"没值"与"空串"）
        if (s == null) {
            return null;
        }
        // 2. 超长则截断到上限
        String text = s.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
