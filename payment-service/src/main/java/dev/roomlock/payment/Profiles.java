package dev.roomlock.payment;

/** Names of the Spring profiles the service supports (rule SP-9). */
public final class Profiles {

  /** Test runs. */
  public static final String TEST = "test";

  /** GCP Cloud Run deployment. */
  public static final String CLOUD = "cloud";

  private Profiles() {}
}
