package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊疗逻辑一致性检查结果。
 */
@Data
public class LogicCheckVO {

    private boolean consistent;
    private List<String> conflicts = new ArrayList<>();
}
