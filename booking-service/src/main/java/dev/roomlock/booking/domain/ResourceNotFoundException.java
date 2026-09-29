package dev.roomlock.booking.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A resource of a read/CRUD path does not exist (rule E-4); the API answers 404 {@code not-found}
 * with {@code resource} and {@code id}.
 */
public class ResourceNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String resource;
  private final UUID id;

  public ResourceNotFoundException(String resource, UUID id) {
    super(resource + " " + id + " not found");
    this.resource = Objects.requireNonNull(resource, "resource");
    this.id = Objects.requireNonNull(id, "id");
  }

  /** Lowercase resource name, e.g. {@code hotel} or {@code room}. */
  public String resource() {
    return resource;
  }

  public UUID id() {
    return id;
  }
}
