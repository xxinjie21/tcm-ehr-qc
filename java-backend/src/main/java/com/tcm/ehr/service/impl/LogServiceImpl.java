package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.domain.po.OperationLog;
import com.tcm.ehr.mapper.OperationLogMapper;
import com.tcm.ehr.service.ILogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志审计实现（批F·7.5）：读 operation_log（批A·C5 双写入库）做分页筛选与导出。
 */
@Service
@RequiredArgsConstructor
public class LogServiceImpl implements ILogService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OperationLogMapper operationLogMapper;

    @Override
    public Map<String, Object> page(String action, String keyword, int page, int size) {
        Page<OperationLog> p = operationLogMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.max(size, 1)), buildWrapper(action, keyword));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", p.getTotal());
        result.put("list", p.getRecords());
        return result;
    }

    @Override
    public List<String> actions() {
        return operationLogMapper.selectDistinctActions();
    }

    @Override
    public List<OperationLog> listForExport(String action, String keyword) {
        return operationLogMapper.selectList(buildWrapper(action, keyword));
    }

    @Override
    public byte[] exportCsv(String action, String keyword) {
        StringBuilder sb = new StringBuilder();
        sb.append("操作时间,操作人,角色,操作类型,操作对象,详情,IP\n");
        for (OperationLog l : listForExport(action, keyword)) {
            sb.append(csv(l.getLogTime() == null ? "" : l.getLogTime().format(TS))).append(',')
                    .append(csv(l.getOperator())).append(',')
                    .append(csv(l.getRole())).append(',')
                    .append(csv(l.getAction())).append(',')
                    .append(csv(l.getTarget())).append(',')
                    .append(csv(l.getDetail())).append(',')
                    .append(csv(l.getIp())).append('\n');
        }
        // UTF-8 BOM：Excel 正确识别中文
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    private QueryWrapper<OperationLog> buildWrapper(String action, String keyword) {
        QueryWrapper<OperationLog> w = new QueryWrapper<>();
        if (action != null && !action.isBlank()) {
            w.eq("action", action.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            w.and(q -> q.like("operator", k).or().like("target", k).or().like("detail", k));
        }
        w.orderByDesc("log_time");
        return w;
    }

    private String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\n") || v.contains("\"")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}
