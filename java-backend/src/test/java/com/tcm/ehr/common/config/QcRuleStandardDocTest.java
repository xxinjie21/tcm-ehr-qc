package com.tcm.ehr.common.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 守卫：改质控规则必须同步改书面标准文档。
 *
 * <p>权威书面标准 = {@code docs/质控规则与评分标准.md}。本测试断言该文档覆盖了默认规则集
 * 的全部条目（完整性要素名 / 格式规则 label / 一致性规则名 / 两个分级阈值），把「更新规则就要
 * 更新规则标准」从口头约定变成 CI 会拦的硬约束。</p>
 *
 * <p>规则一改、文档没跟上 → 本测试失败，并在失败信息里列出缺哪几条，直接可照着补。</p>
 */
class QcRuleStandardDocTest {

    /** 标准文档相对仓库根的路径 */
    private static final String DOC_RELATIVE = "docs/质控规则与评分标准.md";

    @Test
    void 标准文档必须覆盖默认规则集全部条目() throws IOException {
        String doc = readStandardDoc();
        QcRuleSet rules = QcRuleSet.defaults();
        List<String> missing = new ArrayList<>();

        // 1. 完整性：6 个要素名都要出现
        for (QcRuleSet.Element el : rules.getCompleteness().getElements()) {
            if (!doc.contains(el.getName())) {
                missing.add("完整性要素「" + el.getName() + "」");
            }
        }

        // 2. 格式：每条规则的展示名都要出现
        for (QcRuleSet.FormatRule f : rules.getFormat()) {
            String label = f.getLabel() == null ? f.getField() : f.getLabel();
            if (!doc.contains(label)) {
                missing.add("格式规则「" + label + "」");
            }
        }

        // 3. 一致性：14 条规则名逐条核对（最易漏的一类）
        for (QcRuleSet.ConsistencyRule c : rules.getConsistency()) {
            if (!doc.contains(c.getName())) {
                missing.add("一致性规则「" + c.getName() + "」");
            }
        }

        // 4. 分级阈值：两个配置项名与取值都要出现
        QcRuleSet.Thresholds th = rules.getThresholds();
        assertDocHas(doc, missing, "reviewUpper", th.getReviewUpper());
        assertDocHas(doc, missing, "invalid", th.getInvalid());

        // 5. 一处不齐就报全量缺口，不逐条 fail 浪费时间
        if (!missing.isEmpty()) {
            fail("《质控规则与评分标准》未覆盖以下 " + missing.size() + " 项，请同步补登后重跑：\n  - "
                    + String.join("\n  - ", missing));
        }
    }

    /** 阈值项：配置项名与取值都必须出现（只查数字太弱 —— 3 这类数字到处都是） */
    private static void assertDocHas(String doc, List<String> missing, String key, int value) {
        if (!doc.contains(key)) {
            missing.add("阈值项「" + key + "」");
        }
        if (!doc.contains(String.valueOf(value))) {
            missing.add("阈值「" + key + " = " + value + "」");
        }
    }

    /**
     * 读标准文档。文档在仓库根，不在模块内 —— 从工作目录逐级上找（与
     * {@code EntityDdlDriftGuardTest#findDatabaseInitSql} 同法）。
     */
    private static String readStandardDoc() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(DOC_RELATIVE);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException(
                "找不到 " + DOC_RELATIVE + "（从 " + Path.of("").toAbsolutePath() + " 逐级上找）");
    }
}
