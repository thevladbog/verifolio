"use client";

import { useInfiniteQuery } from "@tanstack/react-query";

type Page<T> = { items?: T[]; nextCursor?: string | null };

/** Keyset-cursor pagination over the backend's {items, nextCursor} shape. */
export function useCursorList<T>(
  queryKey: readonly unknown[],
  fetcher: (cursor: string | undefined) => Promise<Page<T>>,
) {
  const query = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam }) => fetcher(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });

  return {
    items: query.data?.pages.flatMap((p) => p.items ?? []) ?? [],
    isLoading: query.isLoading,
    hasNext: query.hasNextPage,
    loadMore: () => query.fetchNextPage(),
    isLoadingMore: query.isFetchingNextPage,
  };
}
