package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ImportTaskVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryTermStore;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 批次 15 的验收压测：导入大 xlsx 不 OOM（原始输出的产出方式见下）。
 *
 * <p><b>怎么用</b>：默认只造 {@value #DEFAULT_ROWS} 行，随常规 `mvn -o test` 快速跑过；
 * 要出「50MB 级不 OOM」的验收证据时放大行数并**限堆**跑：</p>
 *
 * <pre>
 * mvn -o test -Dtest=RecordImportLargeFileTest -Dlarge.rows=200000 -DargLine=-Xmx256m
 * </pre>
 *
 * <p>把行数做成参数而不是写死，是为了不让每次闸门都跑一遍大文件（几十秒）；
 * 而「限堆」必须由调用方给 —— 不限堆的话这个测试无论走流式还是全量载入都能过，等于没测。</p>
 *
 * <p>文件用 {@link SXSSFWorkbook} 流式写、读回时走自实现的 {@link MultipartFile}
 * （直接开文件流，不把整份文件读进 byte[]）—— 否则测试自身就成了内存瓶颈，测不出解析路径。</p>
 */
class RecordImportLargeFileTest {

    private static final int DEFAULT_ROWS = 2000;
    private static final String[] HEADER = {
            "登记号", "门诊号", "性别", "年龄", "就诊次数", "西医诊断", "中医诊断",
            "现病史", "主诉", "自诉", "望诊", "脉诊", "舌诊", "查体", "辨证结论",
            "草药", "随访", "治疗效果", "开单科室", "医生工号", "接诊时间"};

    private RecordServiceImpl svc;

    @BeforeEach
    void setUp() {
        RecordMapper recordMapper = mock(RecordMapper.class);
        when(recordMapper.selectList(any())).thenReturn(new ArrayList<>());
        svc = spy(new RecordServiceImpl(new ObjectMapper(), null, null, mock(IDictionaryTermStore.class)));
        ReflectionTestUtils.setField(svc, "baseMapper", recordMapper);
        doReturn(true).when(svc).saveBatch(any());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestUtils.ATTR_ORG_ID, "org-A");
        req.setAttribute(RequestUtils.ATTR_ROLE, "用户");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void importsLargeXlsxWithinBoundedHeap() throws Exception {
        int rows = Integer.getInteger("large.rows", DEFAULT_ROWS);
        File file = File.createTempFile("records-large-", ".xlsx");
        try {
            writeXlsx(file, rows);
            long sizeMb = file.length() / 1024 / 1024;
            Runtime rt = Runtime.getRuntime();
            long before = rt.totalMemory() - rt.freeMemory();

            ImportTaskVO task = svc.importRecords(new MultipartFile[]{fileBacked(file)}, false);

            long peakMb = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024;
            System.out.println("[LARGE-IMPORT] rows=" + rows + " fileSize=" + sizeMb + "MB"
                    + " maxHeap=" + (rt.maxMemory() / 1024 / 1024) + "MB"
                    + " heapDelta=" + peakMb + "MB"
                    + " total=" + task.getSummary().getTotal() + " failed=" + task.getSummary().getFailed()
                    + " failures=" + task.getSummary().getFailures());

            assertEquals(rows, task.getSummary().getTotal(), "每一行都应被解析到");
            assertEquals(0, task.getSummary().getFailed(), "不该有失败行");
        } finally {
            // noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** 流式写 xlsx：只保留 100 行在内存里，否则测试自己就把堆吃掉了 */
    private static void writeXlsx(File file, int rows) throws Exception {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100); OutputStream out = Files.newOutputStream(file.toPath())) {
            Sheet sheet = wb.createSheet("病历");
            Row h = sheet.createRow(0);
            for (int c = 0; c < HEADER.length; c++) {
                h.createCell(c).setCellValue(HEADER[c]);
            }
            for (int i = 1; i <= rows; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("REG-" + i);
                r.createCell(1).setCellValue("OP-" + i);
                r.createCell(2).setCellValue(i % 2 == 0 ? "男" : "女");
                r.createCell(3).setCellValue("4" + (i % 10));
                r.createCell(4).setCellValue("1");
                r.createCell(5).setCellValue("高血压");
                r.createCell(6).setCellValue("肝郁气滞");
                r.createCell(7).setCellValue("现病史文本" + i);
                r.createCell(8).setCellValue("主诉文本" + i);
                r.createCell(14).setCellValue("肝郁气滞");
                r.createCell(15).setCellValue("柴胡疏肝散");
                r.createCell(20).setCellValue("20221224090613");
            }
            wb.write(out);
            wb.dispose();
        }
    }

    /** 基于文件的 MultipartFile：直接开流，不把整份文件读进 byte[] */
    private static MultipartFile fileBacked(File file) {
        return new MultipartFile() {
            @Override
            public String getName() {
                return "files";
            }

            @Override
            public String getOriginalFilename() {
                return "records-large.xlsx";
            }

            @Override
            public String getContentType() {
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }

            @Override
            public boolean isEmpty() {
                return file.length() == 0;
            }

            @Override
            public long getSize() {
                return file.length();
            }

            @Override
            public byte[] getBytes() {
                throw new UnsupportedOperationException("压测不允许把整份文件读进内存");
            }

            @Override
            public InputStream getInputStream() throws java.io.IOException {
                return new FileInputStream(file);
            }

            @Override
            public void transferTo(File dest) {
                throw new UnsupportedOperationException();
            }
        };
    }

}
