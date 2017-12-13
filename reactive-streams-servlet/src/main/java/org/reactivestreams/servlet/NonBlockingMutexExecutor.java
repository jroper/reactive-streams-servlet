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

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

final class RunNode extends AtomicReference<RunNode> {
  final Runnable runnable;
  RunNode(final Runnable runnable) {
    this.runnable = runnable;
  }
}

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
  private final AtomicReference<RunNode> last = new AtomicReference<>();

  @Override
  public void execute(final Runnable command) {
    final RunNode newNode = new RunNode(Objects.requireNonNull(command, "Runnable must not be null"));
    final RunNode prevLast = last.getAndSet(newNode);
    if (prevLast != null)
      prevLast.lazySet(newNode);
    else
      runAll(newNode);
  }

  protected void reportFailure(final Thread runner, final Runnable thrower, final Throwable thrown) {
      if (thrown instanceof InterruptedException) {
        // TODO: Current task was interrupted, set interrupted flag and proceed is a valid strategy?
        runner.interrupt();
      } else { // TODO: complement the most appropriate way of dealing with fatal Throwables
        final Thread.UncaughtExceptionHandler ueh = runner.getUncaughtExceptionHandler();
        if (ueh != null)
          ueh.uncaughtException(runner, thrown);
        // TODO: Rethrow or something else? Is there a sensible fallback here?
      }
  }

  // Runs a single RunNode and deals with any Throwables it throws
  private final void run(final RunNode current) {
    try { current.runnable.run(); } catch (final Throwable thrown) {
      reportFailure(Thread.currentThread(), current.runnable, thrown);
    }
  }
  
  // Runs all the RunNodes starting with `next`
  private final void runAll(RunNode next) {
    for(;;) {
      final RunNode current = next;
      run(current);
      if ((next = current.get()) == null) { // try advance, if we get null test
        if (last.compareAndSet(current, null)) return; // end-of-queue: we're done.
        else while((next = current.get()) == null); // try advance until next is visible. TODO: Thread.onSpinWait();?
      }
    }
  }
}