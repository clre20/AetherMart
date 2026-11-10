package clre20.aetherMart.gui;

import java.util.List;

/** 分頁：每頁 45 格。 */
public final class Paging {

    public static final int PER_PAGE = 45;

    private Paging() {
    }

    public static int pages(int size) {
        return Math.max(1, (size + PER_PAGE - 1) / PER_PAGE);
    }

    public static int clamp(int page, int size) {
        return Math.max(0, Math.min(page, pages(size) - 1));
    }

    public static <T> List<T> slice(List<T> list, int page) {
        int from = Math.min(list.size(), page * PER_PAGE);
        int to = Math.min(list.size(), from + PER_PAGE);
        return list.subList(from, to);
    }
}
