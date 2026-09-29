CREATE TABLE hotel (
  id   uuid         PRIMARY KEY,
  name varchar(200) NOT NULL,
  city varchar(100) NOT NULL
);

CREATE TABLE room (
  id              uuid          PRIMARY KEY,
  hotel_id        uuid          NOT NULL REFERENCES hotel (id),
  number          varchar(20)   NOT NULL,
  capacity        integer       NOT NULL,
  price_per_night numeric(12,2) NOT NULL,
  version         bigint        NOT NULL DEFAULT 0,
  CONSTRAINT room_capacity_positive CHECK (capacity >= 1),
  CONSTRAINT room_price_per_night_positive CHECK (price_per_night > 0)
);

CREATE INDEX room_hotel_id_idx ON room (hotel_id);
