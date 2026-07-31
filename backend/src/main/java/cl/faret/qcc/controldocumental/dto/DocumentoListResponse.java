package cl.faret.qcc.controldocumental.dto;

import java.util.List;

public class DocumentoListResponse {

    private final List<DocumentoListItemResponse> items;
    private final long total;
    private final int page;
    private final int pageSize;
    private final int pages;

    public DocumentoListResponse(List<DocumentoListItemResponse> items, long total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.pages = (int) Math.ceil(total / (double) pageSize);
    }

    public List<DocumentoListItemResponse> getItems() {
        return items;
    }

    public long getTotal() {
        return total;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getPages() {
        return pages;
    }
}
