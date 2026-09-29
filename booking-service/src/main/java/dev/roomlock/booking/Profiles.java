package dev.roomlock.booking;

/** Names of the Spring profiles the service supports (rule SP-9). */
public final class Profiles {

  /** Test runs: scheduling off, Testcontainers infrastructure. */
  public static final String TEST = "test";

  /** Flyway without the db/constraint location (exclusion constraint off), see D1. */
  public static final String NO_CONSTRAINT = "no-constraint";

  /** GCP Cloud Run deployment. */
  public static final String CLOUD = "cloud";

  private Profiles() {}
}
