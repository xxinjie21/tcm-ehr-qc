package com.tcm.ehr.common.config;

import com.tcm.ehr.domain.vo.NlpExtractVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体类型目录的回归测试（批次 20）。
 *
 *
 * 批次 20 给舌象/脉象/治法建了词典，EntityTypes 的 dict 标志随之翻转。
 *
 * 这个目录是「结构化解析 / 术语词典 / 质控规则」三模块的单一来源，一处写错会
 * 静默传播到播种、导出、版本比对、前端类型下拉多处，所以用测试钉住。
 *
 *
 * 要守住三件事：① 三类确实有了词典且文件名对得上磁盘；② 病因仍无词典
 *
 * （它依赖规则词表，不该被顺手加词典）；③ 目录自身仍然自洽（key/structuredKey
 * 不重复、顺序稳定）。
 */
class EntityTypesDictionaryRegistrationTest {

    /** 批次 20 新增词典的三个类型 */
    private static final List<String> NEW_DICT_TYPES = List.of("tongue", "pulse", "treatment");

    @Test
    @DisplayName("舌象/脉象/治法已标记为有词典，且文件名非空")
    void newTypesHaveDictionary() {
        for (String key : NEW_DICT_TYPES) {
            EntityTypes.EntityType t = EntityTypes.byKey(key);
            assertNotNull(t, key + " 必须存在于实体类型目录");
            assertTrue(t.dict(), key + " 应标记为有词典（批次 20）");
            assertNotNull(t.fileName(), key + " 必须指定词典文件名，否则播种读不到文件");
            assertFalse(t.fileName().isBlank(), key + " 的词典文件名不能为空串");
        }
    }

    @Test
    @DisplayName("词典文件名与实体类型 key 对应得上（tongue → tongues.json）")
    void fileNamesMatchKeys() {
        assertEquals("tongues.json", EntityTypes.fileNameOf("tongue"));
        assertEquals("pulses.json", EntityTypes.fileNameOf("pulse"));
        assertEquals("treatments.json", EntityTypes.fileNameOf("treatment"));
    }

    @Test
    @DisplayName("病因仍无词典（依赖规则词表，不在本批次范围）")
    void causeStillHasNoDictionary() {
        EntityTypes.EntityType cause = EntityTypes.byKey("cause");
        assertNotNull(cause);
        assertFalse(cause.dict(), "病因不在批次 20 范围内，不应有词典");
    }

    @Test
    @DisplayName("structuredKey 能反查到类型（新类型的归一链路依赖它）")
    void structuredKeysAreResolvable() {
        // EntityNormalizer.dictionaryType 就是走这条反查；查不到则舌脉不会走归一
        assertEquals("tongue", EntityTypes.dictTypeByStructuredKey("tongueList").key());
        assertEquals("pulse", EntityTypes.dictTypeByStructuredKey("pulseList").key());
        assertEquals("treatment", EntityTypes.dictTypeByStructuredKey("treatmentList").key());
    }

    @Test
    @DisplayName("key 与 structuredKey 各自不重复（重复会让类型判定歧义）")
    void keysAreUnique() {
        assertEquals(EntityTypes.all().size(),
                EntityTypes.all().stream().map(EntityTypes.EntityType::key).distinct().count(),
                "实体 key 不得重复");
        assertEquals(EntityTypes.all().size(),
                EntityTypes.all().stream().map(EntityTypes.EntityType::structuredKey).distinct().count(),
                "structuredKey 不得重复");
    }

    @Test
    @DisplayName("实体类型仍是 9 类（批次 20 只建词典，不增删实体类型）")
    void stillNineTypes() {
        assertEquals(9, EntityTypes.all().size());
        assertEquals(8, EntityTypes.dictKeys().size(), "8 类有词典，病因除外");
    }

    @Test
    @DisplayName("dictKeys() 是 all() 的过滤视图，不是与 ALL 并列的第二份清单（批次 25.b）")
    void dictKeysIsDerivedFromAll() {
        // 8 与 9 的关系必须是「筛出来的」：若两处各维护一份，新增类型时会静默分叉，
        // 表现为「目录里有、词典播种/未归一判定却看不到」这类无编译错误的漏改。
        List<String> expected = EntityTypes.all().stream()
                .filter(EntityTypes.EntityType::dict)
                .map(EntityTypes.EntityType::key)
                .toList();
        assertEquals(expected, List.copyOf(EntityTypes.dictKeys()));
        // 有词典 ⇔ 文件名非空；无词典 ⇒ 文件名为 null：dict 标志与 fileName 同生同死。
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            assertEquals(t.dict(), t.fileName() != null && !t.fileName().isBlank(),
                    t.key() + " 的 dict 标志与 fileName 不一致");
        }
    }

    @Test
    @DisplayName("NlpExtractVO 覆盖 ALL 的每一个 structuredKey（加类型必须同时加字段）")
    void nlpExtractVoExposesEveryStructuredKey() {
        // 出参是 ALL 的视图：目录加了类型、VO 没加字段，抽取结果就会在响应组装处被丢掉。
        Set<String> voFields = Arrays.stream(NlpExtractVO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (EntityTypes.EntityType t : EntityTypes.all()) {
            assertTrue(voFields.contains(t.structuredKey()),
                    "NlpExtractVO 缺少字段 " + t.structuredKey() + "（类型 " + t.key() + "）");
        }
    }
}