package cl.faret.qcc.noconformidades.dto;

import java.util.List;

public class NoConformidadListResponse {

    private final List<NoConformidadResponse> items;
    private final long total;
    private final int page;
    private final int pageSize;
    private final int pages;

    public NoConformidadListResponse(List<NoConformidadResponse> items, long total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.pages = (int) Math.ceil(total / (double) pageSize);
    }

    public List<NoConformidadResponse> getItems() {
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
