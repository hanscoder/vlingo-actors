// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.actors;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class BasicCompletes<T> implements Completes<T>, Scheduled {
  private final AtomicReference<Outcome<T>> outcome;
  private final State<T> state;

  public BasicCompletes(final Scheduler scheduler) {
    this.outcome = new AtomicReference<>(null);
    this.state = new State<>(scheduler);
  }

  public BasicCompletes(final T outcome) {
    this.outcome = new AtomicReference<>(new Outcome<>(outcome));
    this.state = new State<>();
  }

  private BasicCompletes(final T outcome, final BasicCompletes<T>.State<T> state) {
    this.outcome = new AtomicReference<>(new Outcome<>(outcome));
    this.state = state;
  }

  @Override
  public Completes<T> after(final Supplier<T> supplier) {
    after(supplier, -1L, null);
    return this;
  }

  @Override
  public Completes<T> after(final Supplier<T> supplier, final long timeout) {
    after(supplier, timeout, null);
    return this;
  }

  @Override
  public Completes<T> after(final Supplier<T> supplier, final long timeout, final T timedOutValue) {
    state.timedOutValue = timedOutValue;
    state.actions.add(supplier);
    if (state.isCompleted() && outcome.get() != null) {
      executeActions();
    } else {
      startTimer(timeout);
    }
    return this;
  }

  @Override
  public Completes<T> after(final Consumer<T> consumer) {
    after(consumer, -1L, null);
    return this;
  }

  @Override
  public Completes<T> after(final Consumer<T> consumer, final long timeout) {
    after(consumer, timeout, null);
    return this;
  }

  @Override
  public Completes<T> after(final Consumer<T> consumer, final long timeout, final T timedOutValue) {
    state.timedOutValue = timedOutValue;
    state.actions.add(consumer);
    if (state.isCompleted() && outcome.get() != null) {
      executeActions();
    } else {
      startTimer(timeout);
    }
    return this;
  }

  @Override
  public <R> Completes<R> after(Function<T, R> function) {
    return after(function, -1L, null);
  }

  @Override
  public <R> Completes<R> after(Function<T, R> function, long timeout) {
    return after(function, timeout, null);
  }

  @Override
  public <R> Completes<R> after(Function<T, R> function, long timeout, R timeOutValue) {
    final BasicCompletes<R>.State<R> newState = new BasicCompletes<R>.State<R>(null);
    newState.actions = state.actions;
    newState.cancellable = state.cancellable;
    newState.completed.set(state.completed.get());
    newState.executingActions.set(state.executingActions.get());
    newState.scheduler = state.scheduler;
    newState.timedOutValue = timeOutValue;
    newState.actions.add(function);

    BasicCompletes<R> newCompletes = new BasicCompletes<>(null, newState);
    if (newState.isCompleted() && newCompletes.outcome.get() != null) {
      newCompletes.executeActions();
    } else {
      newCompletes.startTimer(timeout);
    }
    return newCompletes;
  }

  @Override
  public Completes<T> andThen(final Consumer<T> consumer) {
    state.actions.add(consumer);
    if (state.isCompleted() && outcome.get() != null) {
      executeActions();
    }
    return this;
  }

  @Override
  public <R> Completes<R> andThen(Function<T, R> consumer) {
    return null;
  }

  @Override
  public Completes<T> atLast(final Supplier<T> supplier) {
    state.actions.add(supplier);
    if (state.isCompleted() && outcome.get() != null) {
      executeActions();
      outcome.set(new Outcome<>(supplier.get()));
    }
    return this;
  }

  @Override
  public Completes<T> atLast(final Consumer<T> consumer) {
    state.actions.add(consumer);
    if (state.isCompleted() && outcome.get() != null) {
      consumer.accept(outcome.get().data);
    }
    return this;
  }

  @Override
  public T await() {
    return await(-1);
  }

  @Override
  public T await(final long timeout) {
    long countDown = timeout;
    while (true) {
      if (hasOutcome()) {
        return outcome();
      }
      try {
        Thread.sleep((countDown >= 0 && countDown < 100) ? countDown : 100);
      } catch (Exception e) {
        // ignore
      }
      if (hasOutcome()) {
        return outcome();
      }
      if (timeout >= 0) {
        countDown -= 100;
        if (countDown <= 0) {
          return null;
        }
      }
    }
  }

  @Override
  public boolean hasOutcome() {
    return outcome.get() != null;
  }

  @Override
  public T outcome() {
    return outcome.get().data;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <O> Completes<O> with(final O outcome) {
    completedWith(false, (T) outcome);

    return (Completes<O>) this;
  }

  @Override
  public void intervalSignal(final Scheduled scheduled, final Object data) {
    completedWith(true, null);
  }

  void clearOutcome() {
    outcome.set(null);
  }

  private void completedWith(final boolean timedOut, final T outcome) {
    if (state.completed.compareAndSet(false, true)) {
      this.outcome.set(new Outcome<>(outcome));

      state.cancelTimer();

      if (timedOut) {
        this.outcome.set(new Outcome<>(state.timedOutValue));
      }

      executeActions();
    }
  }

  @SuppressWarnings("unchecked")
  private void executeActions() {
    while (!state.executingActions.compareAndSet(false, true))
      ;
    while (state.actions.peek() != null) {
      final Object action = state.actions.poll();
      if (action instanceof Supplier) {
        this.outcome.set(new Outcome(((Supplier<T>) action).get()));
      } else if (action instanceof Consumer) {
        ((Consumer<T>) action).accept(this.outcome.get().data);
      } else if (action instanceof Function) {
        outcome.set(new Outcome<>(((Function<Object, T>) action).apply(outcome.get().data)));
      }
    }
    state.executingActions.set(false);
  }

  private void startTimer(final long timeout) {
    if (timeout > 0) {
      // 2L delayBefore prevents timeout until after return from here
      state.cancellable = state.scheduler.scheduleOnce(this, null, 2L, timeout);
    }
  }

  private class Outcome<T> {
    private T data;

    private Outcome(final T data) {
      this.data = data;
    }
  }

  private class State<T> {
    private Queue<Object> actions;
    private Cancellable cancellable;
    private final AtomicBoolean completed;
    private final AtomicBoolean executingActions;
    private Scheduler scheduler;
    private T timedOutValue;

    private State(final Scheduler scheduler) {
      this.scheduler = scheduler;
      this.actions = new ConcurrentLinkedQueue<>();
      this.completed = new AtomicBoolean(false);
      this.executingActions = new AtomicBoolean(false);
    }

    private State() {
      this(null);
    }

    private void cancelTimer() {
      if (cancellable != null) {
        cancellable.cancel();
        cancellable = null;
      }
    }

    private boolean isCompleted() {
      return completed.get();
    }
  }
}
