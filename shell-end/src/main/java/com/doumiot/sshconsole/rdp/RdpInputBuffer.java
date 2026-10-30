package com.doumiot.sshconsole.rdp;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/** Bounded burst tolerance: preserve all input edges without allowing large-frame memory growth. */
final class RdpInputBuffer {
    private static final int MAX_MESSAGES = 1024;
    private static final int MAX_CHARACTERS = 256 * 1024;
    private final ArrayDeque<String> messages = new ArrayDeque<>();
    private int characters;

    synchronized boolean offer(String input) {
        if (messages.size() >= MAX_MESSAGES || input.length() > MAX_CHARACTERS - characters) return false;
        messages.addLast(input); characters += input.length(); notifyAll(); return true;
    }
    synchronized String poll(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (messages.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        String input = messages.removeFirst(); characters -= input.length(); return input;
    }
    synchronized int size() { return messages.size(); }
    synchronized int characters() { return characters; }
    synchronized void clear() { messages.clear(); characters = 0; notifyAll(); }
}
