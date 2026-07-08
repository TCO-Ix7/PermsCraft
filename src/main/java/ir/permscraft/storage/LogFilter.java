package ir.permscraft.storage;

/**
 * Immutable filter + pagination parameters for log queries.
 * Used by StorageBackend.queryLogs() and StorageBackend.countLogs().
 *
 * All String fields are optional (null = no filter).
 * Long fields (from/to) are epoch seconds (null = unbounded).
 */
public record LogFilter(
        String actor,
        String target,
        String action,
        Long   from,
        Long   to,
        int    limit,
        int    offset
) {}
