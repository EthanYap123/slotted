package dev.ethanyap.slotted.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** A page of results in the API's list shape: {@code items} plus {@code page} metadata. */
public record PageResponse<T>(List<T> items, PageMeta page) {

  public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
    return new PageResponse<>(
        page.getContent().stream().map(mapper).toList(),
        new PageMeta(
            page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
  }

  /** Position of this page within all results. */
  public record PageMeta(int number, int size, long totalElements, int totalPages) {}
}
