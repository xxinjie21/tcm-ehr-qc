package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.CreateRecordDTO;
import com.tcm.ehr.domain.dto.DeleteRecordsDTO;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.CreateRecordVO;
import com.tcm.ehr.domain.vo.DeleteRecordsVO;
import com.tcm.ehr.domain.vo.ImportStatusVO;
import com.tcm.ehr.domain.vo.ImportTaskVO;
import com.tcm.ehr.domain.vo.RawRecordVO;
import com.tcm.ehr.domain.vo.SearchVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 病历数据服务（批F · 成员A 线）。
 * F·7.1 导入/新增/进度；F·7.2 原始查看；F·7.3 修改/删除；F·7.4 多条件查询。
 */
public interface IRecordService extends IService<Record> {

    /** 批量导入病历（.xlsx/.xls 多文件），返回 taskId + 本轮摘要；autoExtract=导入后投后台批量解析任务 */
    ImportTaskVO importRecords(MultipartFile[] files, boolean autoExtract);

    /** 导入进度查询；任务不存在（含服务重启）返回 null，由 Controller 转 404 */
    ImportStatusVO importStatus(String taskId);

    /** 单条新增病历（21 原始字段），返回新病历 id */
    CreateRecordVO createRecord(CreateRecordDTO dto);

    /** 原始病历只读查看（21 字段 + structuredData）；不存在返回 null；越权抛 {@code ForbiddenException} */
    RawRecordVO getRawRecord(String recordId);

    /** 修改病历（仅 structuredData）；body 含原始字段抛 IllegalArgumentException（code=1007） */
    void updateRecord(String recordId, Map<String, Object> body);

    /** 批量删除病历 */
    DeleteRecordsVO deleteRecords(DeleteRecordsDTO dto);

    /** 按筛选范围批量删除病历（管理员；条件全空拒绝，防误删全库） */
    DeleteRecordsVO deleteByFilter(FiltersDTO filters);

    /** 多条件分页查询（数据域 → 用户筛选，取交集） */
    SearchVO searchRecords(SearchDTO dto);
}
