/*
 * Copyright (c) 2008, 2024, Jaroslav Bachorik <j.bachorik@btrace.io>.
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.btrace.mcp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What a deployed probe prints after it starts, kept until a tool reads it. The buffer holds at
 * most a fixed number of characters: when a probe prints more than that between two reads, the
 * oldest whole lines are dropped and the next read says how many.
 */
public final class ProbeOutput {
  /** Enough for a few hundred lines of probe output between two reads. */
  public static final int DEFAULT_MAX_CHARS = 64 * 1024;

  /** How long a waiting read keeps collecting after the last line, so a report stays together. */
  private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(150);

  private final int maxChars;
  private final StringBuilder unread = new StringBuilder();
  private final CountDownLatch finished = new CountDownLatch(1);
  private long droppedLines;
  private long lastAppendNanos;

  public ProbeOutput(int maxChars) {
    this.maxChars = maxChars;
  }

  /** Adds text the probe printed; called from the probe's command loop. */
  public synchronized void append(String text) {
    if (text == null || text.isEmpty()) {
      return;
    }
    unread.append(text);
    lastAppendNanos = System.nanoTime();
    notifyAll();
    int excess = unread.length() - maxChars;
    if (excess <= 0) {
      return;
    }
    // Drop whole lines from the front until the rest fits.
    int cut = unread.indexOf("\n", excess - 1);
    cut = cut < 0 ? unread.length() : cut + 1;
    for (int i = 0; i < cut; i++) {
      if (unread.charAt(i) == '\n') {
        droppedLines++;
      }
    }
    unread.delete(0, cut);
  }

  /** Returns everything printed since the previous call and forgets it. */
  public synchronized String drain() {
    StringBuilder text = new StringBuilder();
    if (droppedLines > 0) {
      text.append('[')
          .append(droppedLines)
          .append(droppedLines == 1 ? " earlier line" : " earlier lines")
          .append(" dropped: the server keeps at most ")
          .append(maxChars / 1024 > 0 ? (maxChars / 1024) + " KB" : maxChars + " characters")
          .append(" of unread output]\n");
      droppedLines = 0;
    }
    text.append(unread);
    unread.setLength(0);
    return text.toString();
  }

  /**
   * Like {@link #drain()}, but when nothing is unread waits up to the given time for the probe to
   * print something. Once output arrives it returns after the probe has been quiet for a moment,
   * so a multi-line report comes back in one read; it also returns when the probe finishes.
   */
  public synchronized String drainAwaiting(long millis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
    while (unread.length() == 0 && droppedLines == 0 && finished.getCount() > 0) {
      long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (remaining <= 0) {
        break;
      }
      wait(remaining);
    }
    // A probe prints a report one line at a time; keep collecting until it has been quiet briefly.
    while (unread.length() > 0 && finished.getCount() > 0) {
      long now = System.nanoTime();
      long quietLeft = QUIET_NANOS - (now - lastAppendNanos);
      long left = Math.min(quietLeft, deadline - now);
      if (left <= 0) {
        break;
      }
      TimeUnit.NANOSECONDS.timedWait(this, left);
    }
    return drain();
  }

  /** Marks the probe's session as over: the agent confirmed the exit or the connection closed. */
  public synchronized void finish() {
    finished.countDown();
    notifyAll();
  }

  /** Returns whether the probe's session is over. */
  public boolean isFinished() {
    return finished.getCount() == 0;
  }

  /** Waits up to the given time for {@link #finish()}; returns whether it happened. */
  public boolean awaitFinished(long millis) throws InterruptedException {
    return finished.await(millis, TimeUnit.MILLISECONDS);
  }

  /** Formats drained output as a titled section of a tool result, or "" when there is none. */
  public static String section(String title, String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return "\n" + title + ":\n" + text + (text.endsWith("\n") ? "" : "\n");
  }
}
