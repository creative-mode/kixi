package ao.creativemode.kixi.shared.dto;

import java.util.List;

/**
 * One page of a longer listing, with what the client needs to draw the pager:
 * the zero-based page asked for, its size, and the total across every page.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
