package dev.roomlock.booking.support;

import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A transaction manager for tests that script a write instead of running one: a hold strategy opens
 * a transaction per attempt, and a scripted attempt has no resource to enlist.
 */
public final class NoTransactions implements PlatformTransactionManager {

  @Override
  public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
    return new SimpleTransactionStatus();
  }

  @Override
  public void commit(TransactionStatus status) {
    // nothing was written
  }

  @Override
  public void rollback(TransactionStatus status) {
    // nothing was written
  }
}
