package dev.roomlock.booking.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body of {@code POST /api/v1/hotels}; sizes match the columns (rule V-1). */
record CreateHotelRequest(
    @NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 100) String city) {}
