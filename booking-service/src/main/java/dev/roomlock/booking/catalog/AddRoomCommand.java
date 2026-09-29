package dev.roomlock.booking.catalog;

import java.math.BigDecimal;
import java.util.UUID;

public record AddRoomCommand(UUID hotelId, String number, int capacity, BigDecimal pricePerNight) {}
