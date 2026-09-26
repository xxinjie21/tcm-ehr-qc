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
 * 病历数据服务：导入、新增、查看、修改、删除、多条件查询。
 *
 * <p>原始 21 字段一经写入只读，允许修改的只有结构化数据。</p>
 */
public interface IRecordService extends IService<Record> {

    /**
     * 批量导入病历。
     *
     * @param files       病历文件（.xlsx/.xls）
     * @param autoExtract 是否在入库后投递后台批量解析任务
     * @return taskId=导入任务ID；summary=本轮成功/失败条数与失败明细
     */
    ImportTaskVO importRecords(MultipartFile[] files, boolean autoExtract);

    /**
     * 查询导入进度。
     *
     * @param taskId 导入任务ID
     * @return 任务状态与进度；任务不存在（含服务重启）返回 {@code null}，由 Controller 转 404
     */
    ImportStatusVO importStatus(String taskId);

    /**
     * 单条新增病历。
     *
     * @param dto 21 个原始字段，登记号必填
     * @return id=新病历ID
     */
    CreateRecordVO createRecord(CreateRecordDTO dto);

    /**
     * 只读查看病历原始数据。
     *
     * @param recordId 病历ID
     * @return 21 原始字段 + 结构化数据 + 评分；不存在返回 {@code null}，越权抛 ForbiddenException
     */
    RawRecordVO getRawRecord(String recordId);

    /**
     * 修改病历的结构化数据。
     *
     * @param recordId 病历ID
     * @param body     只接受 structuredData 键，携带原始字段按只读冲突抛 1007
     */
    void updateRecord(String recordId, Map<String, Object> body);

    /**
     * 按 ID 批量删除病历。
     *
     * @param dto ids=待删除的病历ID集合
     * @return deletedCount=实际删除条数
     */
    DeleteRecordsVO deleteRecords(DeleteRecordsDTO dto);

    /**
     * 按筛选范围批量删除病历。
     *
     * @param filters 范围条件，至少一项非空
     * @return deletedCount=实际删除条数
     */
    DeleteRecordsVO deleteByFilter(FiltersDTO filters);

    /**
     * 多条件分页查询。
     *
     * @param dto 查询条件与分页参数，先数据域再用户筛选
     * @return total=总条数；records=当前页摘要列表
     */
    SearchVO searchRecords(SearchDTO dto);
}
