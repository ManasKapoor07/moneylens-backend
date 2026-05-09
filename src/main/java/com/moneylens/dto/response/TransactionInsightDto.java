package com.moneylens.dto.response;

import com.moneylens.entity.TransactionInsight;

import java.util.UUID;

public class TransactionInsightDto {

    private UUID id;
    private String type;
    private String label;
    private String value;
    private String meta;

    private TransactionInsightDto() {}

    public static TransactionInsightDto from(TransactionInsight i) {
        TransactionInsightDto dto = new TransactionInsightDto();
        dto.id    = i.getId();
        dto.type  = i.getType();
        dto.label = i.getLabel();
        dto.value = i.getValue();
        dto.meta  = i.getMeta();
        return dto;
    }

    public UUID getId()     { return id; }
    public String getType() { return type; }
    public String getLabel(){ return label; }
    public String getValue(){ return value; }
    public String getMeta() { return meta; }
}