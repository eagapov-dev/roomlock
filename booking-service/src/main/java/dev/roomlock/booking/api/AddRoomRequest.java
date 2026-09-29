package dev.roomlock.booking.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request body of {@code POST /api/v1/hotels/{hotelId}/rooms}. The hotel comes from the path, the
 * constraints repeat the {@code room} column types and CHECKs (rule V-1).
 */
record AddRoomRequest(
    @NotBlank @Size(max = 20) String number,
    @Min(1) int capacity,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
        BigDecimal pricePerNight) {}
