package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.DictionaryLintVO;

import java.util.List;

/**
 * 词表质量体检（批次 21）。
 *
 * 与其它词典相关的服务刻意分开：体检是纯计算，不读库、不写库、不碰 ES，
 * 可以对着任意一份词表离线跑。理由是词表通常在入库前要核对好几轮，
 * 让每次核对都走一次「上传 → 落库 → 重建索引」代价太大而且会留下垃圾数据。
 */
public interface IDictionaryLintService {

    /**
     * 体检一份词表。
     *
     * @param type    术语类型（disease/pattern/…），用于给出针对性建议
     * @param entries 待体检的词条
     * @return 体检结论：硬错误 + 警告 + 高频问题
     */
    DictionaryLintVO lint(String type, List<TermEntry> entries);

    /**
     * 体检一份词表文件内容（Excel/CSV/JSON 都先解析成词条再走同一个体检）。
     *
     *
     * 与 {@link #lint} 分开是因为「解析」要处理编码、扩展名、大小，
     *
     * 属于 IO 层；而 {@link #lint} 是纯逻辑，可以脱离文件单独测。
     *
     * @param type    术语类型
     * @param entries 已解析的词条
     * @return 体检结论
     */
    default DictionaryLintVO lintParsed(String type, List<TermEntry> entries) {
        return lint(type, entries);
    }
}