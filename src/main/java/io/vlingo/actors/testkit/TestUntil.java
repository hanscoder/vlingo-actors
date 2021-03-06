// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.actors.testkit;

import java.util.concurrent.CountDownLatch;

public final class TestUntil {
  private CountDownLatch latch;
  private final boolean zero;
  
  public static TestUntil happenings(final int times) {
    final TestUntil waiter = new TestUntil(times);
    return waiter;
  }

  public void completeNow() {
    while (latch.getCount() > 0) {
      happened();
    }
  }

  public void completes() {
    if (zero) {
      try {
        Thread.sleep(10);
      } catch (Exception e) {
        // ignore
      }
    } else {
      try {
        latch.await();
      } catch (Exception e) {
        // ignore
      }
    }
  }

  public boolean completesWithin(final long timeout) {
    long countDown = timeout;
    while (true) {
      if (latch.getCount() == 0) {
        return true;
      }
      try {
        Thread.sleep((countDown >= 0 && countDown < 100) ? countDown : 100);
      } catch (Exception e) {
        // ignore
      }
      if (latch.getCount() == 0) {
        return true;
      }
      if (timeout >= 0) {
        countDown -= 100;
        if (countDown <= 0) {
          return false;
        }
      }
    }
  }

  public TestUntil happened() {
    latch.countDown();
    return this;
  }

  public int remaining() {
    return (int) latch.getCount();
  }

  public void resetHappeningsTo(final int times) {
    this.latch = new CountDownLatch(times);
  }

  @Override
  public String toString() {
    return "TestUntil[count=" + latch.getCount() + ", zero=" + zero + "]";
  }

  private TestUntil(final int count) {
    this.latch = new CountDownLatch(count);
    this.zero = count == 0;
  }
}
