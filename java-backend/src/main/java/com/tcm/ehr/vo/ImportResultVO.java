package com.tcm.ehr.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class ImportResultVO {

    private String type;
    private int total;
    private int imported;
    private int failed;
    private List<Map<String, Object>> failures = new ArrayList<>();
}
