package cl.faret.qcc.registroscontrol.dto;

import java.util.List;

public class RegistroControlListResponse {

    private final List<RegistroControlItemResponse> items;
    private final long total;
    private final int page;
    private final int pageSize;

    public RegistroControlListResponse(
            List<RegistroControlItemResponse> items, long total, int page, int pageSize) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public List<RegistroControlItemResponse> getItems() {
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
}
