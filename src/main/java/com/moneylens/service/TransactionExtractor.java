package com.moneylens.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Async entry point only.  Delegates all real work to TransactionExtractionWorker
 * so that @Async and @Transactional live on different beans and both proxies
 * compose correctly.
 *
 * Acyclic dependency graph:
 *   TransactionExtractor  →  TransactionExtractionWorker  →  TransactionMapper
 */
@Service
public class TransactionExtractor {

    private static final Logger log = LoggerFactory.getLogger(TransactionExtractor.class);

    private final TransactionExtractionWorker worker;

    public TransactionExtractor(TransactionExtractionWorker worker) {
        this.worker = worker;
    }

    @Async
    public void extract(UUID statementId, List<Map<String, String>> rawRows) {
        log.info("Async extraction queued for statement: {}", statementId);
        worker.doExtract(statementId, rawRows);
    }
}