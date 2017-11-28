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
  public void execute(Runnable command) {
    final RunNode newNode = new RunNode(Objects.requireNonNull(command, "Runnable must not be null"));
    final RunNode prevLast = last.getAndSet(newNode);
    // We changed from null to a list of ops, that's mean it's our responsibility to run it
    if (prevLast == null) {
      executeAll(newNode);
    } else
      prevLast.lazySet(newNode);
  }

  private void executeAll(RunNode next) {
    RunNode prev = next;
    for(;;) {
      final RunNode current = next;
      if (current != null) {
        try {
          current.runnable.run();
        } catch (RuntimeException e) {
          e.printStackTrace();
        } finally {
          prev = current;
          next = current.get();
        }
      } else {
        if(last.compareAndSet(prev, null)) break;
        else next = prev.get();
      }
    }
  }
}