-- A room number is unique inside its hotel; the unique index also backs the ON CONFLICT clause
-- of RoomRepository.insertIfNumberFree. The name matches the PostgreSQL default for the pair.
ALTER TABLE room ADD CONSTRAINT room_hotel_id_number_key UNIQUE (hotel_id, number);
