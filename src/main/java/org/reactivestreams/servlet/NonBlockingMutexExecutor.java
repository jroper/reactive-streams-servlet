/************************************************************************
 * Licensed under Public Domain (CC0)                                    *
 *                                                                       *
 * To the extent possible under law, the person who associated CC0 with  *
 * this code has waived all copyright and related or neighboring         *
 * rights to this code.                                                  *
 *                                                                       *
 * You should have received a copy of the CC0 legalcode along with this  *
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.*
 ************************************************************************/
package org.reactivestreams.servlet;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executor that provides mutual exclusion between the operations submitted to it,
 * without blocking.
 *
 * If an operation is submitted to this executor while no other operation is
 * running, it will run immediately.
 *
 * If an operation is submitted to this executor while another operation is
 * running, it will be added to a queue of operations to run, and the executor will
 * return. The thread currently running an operation will end up running the
 * operation just submitted.
 *
 * Operations submitted to this executor should run fast, as they can end up running
 * on other threads and interfere with the operation of other threads.
 *
 * This executor can also be used to address infinite recursion problems, as
 * operations submitted recursively will run sequentially.
 */
class NonBlockingMutexExecutor implements Executor {

  /**
   * We use an array for a queue, since the JDK doesn't provide any built in
   * persistent collections, and these queues are generally going to be short enough
   * that array copies won't be expensive (and will probably be cheaper than a
   * persistent collection anyway).
   */
  private final AtomicReference<Runnable[]> state = new AtomicReference<>(null);

  private static final Runnable[] empty = new Runnable[0];

  @Override
  public void execute(Runnable command) {
    Runnable[] prevState;
    Runnable[] newState;

    do {
      prevState = state.get();
      if (prevState == null) {
        newState = empty;
      } else {
        newState = new Runnable[prevState.length + 1];
        System.arraycopy(prevState, 0, newState, 0, prevState.length);
        newState[prevState.length] = command;
      }
    } while (!state.compareAndSet(prevState, newState));

    if (prevState == null) {
      // We changed from null to a list of ops, that's mean it's our responsibility to run it
      executeAll(command);
    }

  }

  private void executeAll(Runnable command) {
    while (command != null) {
      try {
        command.run();
      } catch (RuntimeException e) {
        e.printStackTrace();
      }
      command = dequeueNextOpToExecute();
    }
  }

  private Runnable dequeueNextOpToExecute() {
    Runnable[] prevState;
    Runnable[] newState;
    Runnable nextOp;

    do {
      prevState = state.get();
      if (prevState == null) {
        throw new IllegalStateException("Must have a queue of pending elements while executing");
      } else if (prevState.length == 0) {
        nextOp = null;
        newState = null;
      } else {
        nextOp = prevState[0];
        if (prevState.length == 1) {
          newState = empty;
        } else {
          newState = new Runnable[prevState.length - 1];
          System.arraycopy(prevState, 1, newState, 0, newState.length);
        }
      }
    } while (!state.compareAndSet(prevState, newState));

    return nextOp;
  }

}