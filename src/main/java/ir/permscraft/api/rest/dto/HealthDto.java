package ir.permscraft.api.rest.dto;

/**
 * Response body for GET /api/v1/health
 */
public record HealthDto(
        String status,
        String version,
        String server,
        long   timestamp
) {}
