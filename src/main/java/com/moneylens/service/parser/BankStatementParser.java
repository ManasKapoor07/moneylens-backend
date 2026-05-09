package com.moneylens.service.parser;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface BankStatementParser {
    List<Map<String, String>> parse(Path filePath, String contentType) throws Exception;
    boolean supports(String bankName);
}