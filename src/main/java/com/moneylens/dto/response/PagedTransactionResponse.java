package com.moneylens.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paginated wrapper for transaction lists.
 * Used by GET /api/v1/statements/{id}/transactions
 */
public class PagedTransactionResponse {

    private List<TransactionDto> content;
    private int     page;
    private int     size;
    private long    totalElements;
    private int     totalPages;
    private boolean first;
    private boolean last;

    private PagedTransactionResponse() {}

    public static PagedTransactionResponse from(Page<TransactionDto> page) {
        PagedTransactionResponse r = new PagedTransactionResponse();
        r.content       = page.getContent();
        r.page          = page.getNumber();
        r.size          = page.getSize();
        r.totalElements = page.getTotalElements();
        r.totalPages    = page.getTotalPages();
        r.first         = page.isFirst();
        r.last          = page.isLast();
        return r;
    }

    public List<TransactionDto> getContent() { return content; }
    public int getPage()                     { return page; }
    public int getSize()                     { return size; }
    public long getTotalElements()           { return totalElements; }
    public int getTotalPages()               { return totalPages; }
    public boolean isFirst()                 { return first; }
    public boolean isLast()                  { return last; }
}