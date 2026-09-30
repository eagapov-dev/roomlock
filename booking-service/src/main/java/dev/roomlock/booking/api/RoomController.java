package dev.roomlock.booking.api;

import dev.roomlock.booking.catalog.AddRoomCommand;
import dev.roomlock.booking.catalog.AddRoomResult;
import dev.roomlock.booking.catalog.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Rooms of the catalog: created under their hotel, read by their own id. The controller only maps
 * HTTP to the use case (rule A-2) and every outcome of {@link AddRoomResult} to a status.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catalog", description = "Hotels and rooms")
class RoomController {

  private static final String HOTEL_ROOMS = "/hotels/{hotelId}/rooms";
  private static final String ROOM = "/rooms/{id}";

  private final CatalogService catalog;
  private final Problems problems;

  RoomController(CatalogService catalog, Problems problems) {
    this.catalog = catalog;
    this.problems = problems;
  }

  @PostMapping(HOTEL_ROOMS)
  @Operation(summary = "Add a room to a hotel")
  @SecurityRequirement(name = OpenApiConfig.BEARER)
  ResponseEntity<?> add(@PathVariable UUID hotelId, @Valid @RequestBody AddRoomRequest request) {
    AddRoomResult result =
        catalog.addRoom(
            new AddRoomCommand(
                hotelId, request.number(), request.capacity(), request.pricePerNight()));
    return switch (result) {
      case AddRoomResult.Added added ->
          ResponseEntity.created(roomLocation(added.room().getId()))
              .body(RoomResponse.of(added.room()));
      case AddRoomResult.NumberTaken taken ->
          ResponseEntity.status(HttpStatus.CONFLICT)
              .body(problems.roomNumberTaken(taken.hotelId(), taken.number()));
    };
  }

  @GetMapping(ROOM)
  @Operation(summary = "Read a room")
  RoomResponse get(@PathVariable UUID id) {
    return RoomResponse.of(catalog.getRoom(id));
  }

  private static URI roomLocation(UUID roomId) {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/api/v1" + ROOM)
        .buildAndExpand(roomId)
        .toUri();
  }
}
