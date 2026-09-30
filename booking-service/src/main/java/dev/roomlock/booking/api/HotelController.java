package dev.roomlock.booking.api;

import dev.roomlock.booking.catalog.CatalogService;
import dev.roomlock.booking.catalog.CreateHotelCommand;
import dev.roomlock.booking.domain.Hotel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Hotels of the catalog. The controller only maps HTTP to the use case (rule A-2). */
@RestController
@RequestMapping(HotelController.HOTELS)
@Tag(name = "Catalog", description = "Hotels and rooms")
class HotelController {

  static final String HOTELS = "/api/v1/hotels";

  private final CatalogService catalog;

  HotelController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @PostMapping
  @Operation(summary = "Create a hotel")
  @SecurityRequirement(name = OpenApiConfig.BEARER)
  ResponseEntity<HotelResponse> create(@Valid @RequestBody CreateHotelRequest request) {
    Hotel hotel = catalog.createHotel(new CreateHotelCommand(request.name(), request.city()));
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(hotel.getId())
            .toUri();
    return ResponseEntity.created(location).body(HotelResponse.of(hotel));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Read a hotel")
  HotelResponse get(@PathVariable UUID id) {
    return HotelResponse.of(catalog.getHotel(id));
  }
}
