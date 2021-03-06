package org.apache.lucene.util.automaton;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.IntsRef;
import org.apache.lucene.util.XIntsRefBuilder;
import org.apache.lucene.util.TestUtil;
import org.apache.lucene.util.XUnicodeUtil;

/**
 * Utilities for testing automata.
 * <p>
 * Capable of generating random regular expressions,
 * and automata, and also provides a number of very
 * basic unoptimized implementations (*slow) for testing.
 */
public class AutomatonTestUtil {
  /**
   * Default maximum number of states that {@link XOperations#determinize} should create.
   */
  public static final int DEFAULT_MAX_DETERMINIZED_STATES = 1000000;

  /** Returns random string, including full unicode range. */
  public static String randomRegexp(Random r) {
    while (true) {
      String regexp = randomRegexpString(r);
      // we will also generate some undefined unicode queries
      if (!XUnicodeUtil.validUTF16String(regexp))
        continue;
      try {
        new XRegExp(regexp, XRegExp.NONE);
        return regexp;
      } catch (Exception e) {}
    }
  }

  private static String randomRegexpString(Random r) {
    final int end = r.nextInt(20);
    if (end == 0) {
      // allow 0 length
      return "";
    }
    final char[] buffer = new char[end];
    for (int i = 0; i < end; i++) {
      int t = r.nextInt(15);
      if (0 == t && i < end - 1) {
        // Make a surrogate pair
        // High surrogate
        buffer[i++] = (char) TestUtil.nextInt(r, 0xd800, 0xdbff);
        // Low surrogate
        buffer[i] = (char) TestUtil.nextInt(r, 0xdc00, 0xdfff);
      }
      else if (t <= 1) buffer[i] = (char) r.nextInt(0x80);
      else if (2 == t) buffer[i] = (char) TestUtil.nextInt(r, 0x80, 0x800);
      else if (3 == t) buffer[i] = (char) TestUtil.nextInt(r, 0x800, 0xd7ff);
      else if (4 == t) buffer[i] = (char) TestUtil.nextInt(r, 0xe000, 0xffff);
      else if (5 == t) buffer[i] = '.';
      else if (6 == t) buffer[i] = '?';
      else if (7 == t) buffer[i] = '*';
      else if (8 == t) buffer[i] = '+';
      else if (9 == t) buffer[i] = '(';
      else if (10 == t) buffer[i] = ')';
      else if (11 == t) buffer[i] = '-';
      else if (12 == t) buffer[i] = '[';
      else if (13 == t) buffer[i] = ']';
      else if (14 == t) buffer[i] = '|';
    }
    return new String(buffer, 0, end);
  }
  
  /** picks a random int code point, avoiding surrogates;
   * throws IllegalArgumentException if this transition only
   * accepts surrogates */
  private static int getRandomCodePoint(final Random r, int min, int max) {
    final int code;
    if (max < XUnicodeUtil.UNI_SUR_HIGH_START ||
        min > XUnicodeUtil.UNI_SUR_HIGH_END) {
      // easy: entire range is before or after surrogates
      code = min+r.nextInt(max-min+1);
    } else if (min >= XUnicodeUtil.UNI_SUR_HIGH_START) {
      if (max > XUnicodeUtil.UNI_SUR_LOW_END) {
        // after surrogates
        code = 1+XUnicodeUtil.UNI_SUR_LOW_END+r.nextInt(max-XUnicodeUtil.UNI_SUR_LOW_END);
      } else {
        throw new IllegalArgumentException("transition accepts only surrogates: min=" + min + " max=" + max);
      }
    } else if (max <= XUnicodeUtil.UNI_SUR_LOW_END) {
      if (min < XUnicodeUtil.UNI_SUR_HIGH_START) {
        // before surrogates
        code = min + r.nextInt(XUnicodeUtil.UNI_SUR_HIGH_START - min);
      } else {
        throw new IllegalArgumentException("transition accepts only surrogates: min=" + min + " max=" + max);
      }
    } else {
      // range includes all surrogates
      int gap1 = XUnicodeUtil.UNI_SUR_HIGH_START - min;
      int gap2 = max - XUnicodeUtil.UNI_SUR_LOW_END;
      int c = r.nextInt(gap1+gap2);
      if (c < gap1) {
        code = min + c;
      } else {
        code = XUnicodeUtil.UNI_SUR_LOW_END + c - gap1 + 1;
      }
    }

    assert code >= min && code <= max && (code < XUnicodeUtil.UNI_SUR_HIGH_START || code > XUnicodeUtil.UNI_SUR_LOW_END):
      "code=" + code + " min=" + min + " max=" + max;
    return code;
  }

  /**
   * Lets you retrieve random strings accepted
   * by an Automaton.
   * <p>
   * Once created, call {@link #getRandomAcceptedString(Random)}
   * to get a new string (in UTF-32 codepoints).
   */
  public static class RandomAcceptedStrings {

    private final Map<XTransition,Boolean> leadsToAccept;
    private final XAutomaton a;
    private final XTransition[][] transitions;

    private static class ArrivingTransition {
      final int from;
      final XTransition t;

      public ArrivingTransition(int from, XTransition t) {
        this.from = from;
        this.t = t;
      }
    }

    public RandomAcceptedStrings(XAutomaton a) {
      this.a = a;
      if (a.getNumStates() == 0) {
        throw new IllegalArgumentException("this automaton accepts nothing");
      }
      this.transitions = a.getSortedTransitions();

      leadsToAccept = new HashMap<>();
      final Map<Integer,List<ArrivingTransition>> allArriving = new HashMap<>();

      final LinkedList<Integer> q = new LinkedList<>();
      final Set<Integer> seen = new HashSet<>();

      // reverse map the transitions, so we can quickly look
      // up all arriving transitions to a given state
      int numStates = a.getNumStates();
      for(int s=0;s<numStates;s++) {
        for(XTransition t : transitions[s]) {
          List<ArrivingTransition> tl = allArriving.get(t.dest);
          if (tl == null) {
            tl = new ArrayList<>();
            allArriving.put(t.dest, tl);
          }
          tl.add(new ArrivingTransition(s, t));
        }
        if (a.isAccept(s)) {
          q.add(s);
          seen.add(s);
        }
      }

      // Breadth-first search, from accept states,
      // backwards:
      while (q.isEmpty() == false) {
        final int s = q.removeFirst();
        List<ArrivingTransition> arriving = allArriving.get(s);
        if (arriving != null) {
          for(ArrivingTransition at : arriving) {
            final int from = at.from;
            if (!seen.contains(from)) {
              q.add(from);
              seen.add(from);
              leadsToAccept.put(at.t, Boolean.TRUE);
            }
          }
        }
      }
    }

    public int[] getRandomAcceptedString(Random r) {

      final List<Integer> soFar = new ArrayList<>();

      int s = 0;

      while(true) {
      
        if (a.isAccept(s)) {
          if (a.getNumTransitions(s) == 0) {
            // stop now
            break;
          } else {
            if (r.nextBoolean()) {
              break;
            }
          }
        }

        if (a.getNumTransitions(s) == 0) {
          throw new RuntimeException("this automaton has dead states");
        }

        boolean cheat = r.nextBoolean();

        final XTransition t;
        if (cheat) {
          // pick a transition that we know is the fastest
          // path to an accept state
          List<XTransition> toAccept = new ArrayList<>();
          for(XTransition t0 : transitions[s]) {
            if (leadsToAccept.containsKey(t0)) {
              toAccept.add(t0);
            }
          }
          if (toAccept.size() == 0) {
            // this is OK -- it means we jumped into a cycle
            t = transitions[s][r.nextInt(transitions[s].length)];
          } else {
            t = toAccept.get(r.nextInt(toAccept.size()));
          }
        } else {
          t = transitions[s][r.nextInt(transitions[s].length)];
        }
        soFar.add(getRandomCodePoint(r, t.min, t.max));
        s = t.dest;
      }

      return ArrayUtil.toIntArray(soFar);
    }
  }
  
  /** return a random NFA/DFA for testing */
  public static XAutomaton randomAutomaton(Random random) {
    // get two random Automata from regexps
    XAutomaton a1 = new XRegExp(AutomatonTestUtil.randomRegexp(random), XRegExp.NONE).toAutomaton();
    if (random.nextBoolean()) {
      a1 = XOperations.complement(a1, DEFAULT_MAX_DETERMINIZED_STATES);
    }
    
    XAutomaton a2 = new XRegExp(AutomatonTestUtil.randomRegexp(random), XRegExp.NONE).toAutomaton();
    if (random.nextBoolean()) {
      a2 = XOperations.complement(a2, DEFAULT_MAX_DETERMINIZED_STATES);
    }

    // combine them in random ways
    switch (random.nextInt(4)) {
      case 0: return XOperations.concatenate(a1, a2);
      case 1: return XOperations.union(a1, a2);
      case 2: return XOperations.intersection(a1, a2);
      default: return XOperations.minus(a1, a2, DEFAULT_MAX_DETERMINIZED_STATES);
    }
  }
  
  /** 
   * below are original, unoptimized implementations of DFA operations for testing.
   * These are from brics automaton, full license (BSD) below:
   */
  
  /*
   * dk.brics.automaton
   * 
   * Copyright (c) 2001-2009 Anders Moeller
   * All rights reserved.
   * 
   * Redistribution and use in source and binary forms, with or without
   * modification, are permitted provided that the following conditions
   * are met:
   * 1. Redistributions of source code must retain the above copyright
   *    notice, this list of conditions and the following disclaimer.
   * 2. Redistributions in binary form must reproduce the above copyright
   *    notice, this list of conditions and the following disclaimer in the
   *    documentation and/or other materials provided with the distribution.
   * 3. The name of the author may not be used to endorse or promote products
   *    derived from this software without specific prior written permission.
   * 
   * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
   * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
   * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
   * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
   * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
   * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
   * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
   * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
   * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
   * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
   */

  /**
   * Simple, original brics implementation of Brzozowski minimize()
   */
  public static XAutomaton minimizeSimple(XAutomaton a) {
    Set<Integer> initialSet = new HashSet<Integer>();
    a = determinizeSimple(XOperations.reverse(a, initialSet), initialSet);
    initialSet.clear();
    a = determinizeSimple(XOperations.reverse(a, initialSet), initialSet);
    return a;
  }
  
  /**
   * Simple, original brics implementation of determinize()
   */
  public static XAutomaton determinizeSimple(XAutomaton a) {
    Set<Integer> initialset = new HashSet<>();
    initialset.add(0);
    return determinizeSimple(a, initialset);
  }

  /** 
   * Simple, original brics implementation of determinize()
   * Determinizes the given automaton using the given set of initial states. 
   */
  public static XAutomaton determinizeSimple(XAutomaton a, Set<Integer> initialset) {
    if (a.getNumStates() == 0) {
      return a;
    }
    int[] points = a.getStartPoints();
    // subset construction
    Map<Set<Integer>, Set<Integer>> sets = new HashMap<>();
    LinkedList<Set<Integer>> worklist = new LinkedList<>();
    Map<Set<Integer>, Integer> newstate = new HashMap<>();
    sets.put(initialset, initialset);
    worklist.add(initialset);
    XAutomaton.Builder result = new XAutomaton.Builder();
    result.createState();
    newstate.put(initialset, 0);
    XTransition t = new XTransition();
    while (worklist.size() > 0) {
      Set<Integer> s = worklist.removeFirst();
      int r = newstate.get(s);
      for (int q : s) {
        if (a.isAccept(q)) {
          result.setAccept(r, true);
          break;
        }
      }
      for (int n = 0; n < points.length; n++) {
        Set<Integer> p = new HashSet<>();
        for (int q : s) {
          int count = a.initTransition(q, t);
          for(int i=0;i<count;i++) {
            a.getNextTransition(t);
            if (t.min <= points[n] && points[n] <= t.max) {
              p.add(t.dest);
            }
          }
        }

        if (!sets.containsKey(p)) {
          sets.put(p, p);
          worklist.add(p);
          newstate.put(p, result.createState());
        }
        int q = newstate.get(p);
        int min = points[n];
        int max;
        if (n + 1 < points.length) {
          max = points[n + 1] - 1;
        } else {
          max = Character.MAX_CODE_POINT;
        }
        result.addTransition(r, q, min, max);
      }
    }

    return XOperations.removeDeadStates(result.finish());
  }

  /**
   * Simple, original implementation of getFiniteStrings.
   *
   * <p>Returns the set of accepted strings, assuming that at most
   * <code>limit</code> strings are accepted. If more than <code>limit</code> 
   * strings are accepted, the first limit strings found are returned. If <code>limit</code>&lt;0, then 
   * the limit is infinite.
   *
   * <p>This implementation is recursive: it uses one stack
   * frame for each digit in the returned strings (ie, max
   * is the max length returned string).
   */
  public static Set<IntsRef> getFiniteStringsRecursive(XAutomaton a, int limit) {
    HashSet<IntsRef> strings = new HashSet<>();
    if (!getFiniteStrings(a, 0, new HashSet<Integer>(), strings, new XIntsRefBuilder(), limit)) {
      return strings;
    }
    return strings;
  }

  /**
   * Returns the strings that can be produced from the given state, or
   * false if more than <code>limit</code> strings are found. 
   * <code>limit</code>&lt;0 means "infinite".
   */
  private static boolean getFiniteStrings(XAutomaton a, int s, HashSet<Integer> pathstates, 
      HashSet<IntsRef> strings, XIntsRefBuilder path, int limit) {
    pathstates.add(s);
    XTransition t = new XTransition();
    int count = a.initTransition(s, t);
    for (int i=0;i<count;i++) {
      a.getNextTransition(t);
      if (pathstates.contains(t.dest)) {
        return false;
      }
      for (int n = t.min; n <= t.max; n++) {
        path.append(n);
        if (a.isAccept(t.dest)) {
          strings.add(path.toIntsRef());
          if (limit >= 0 && strings.size() > limit) {
            return false;
          }
        }
        if (!getFiniteStrings(a, t.dest, pathstates, strings, path, limit)) {
          return false;
        }
        path.setLength(path.length() - 1);
      }
    }
    pathstates.remove(s);
    return true;
  }

  /**
   * Returns true if the language of this automaton is finite.
   * <p>
   * WARNING: this method is slow, it will blow up if the automaton is large.
   * this is only used to test the correctness of our faster implementation.
   */
  public static boolean isFiniteSlow(XAutomaton a) {
    if (a.getNumStates() == 0) {
      return true;
    }
    return isFiniteSlow(a, 0, new HashSet<Integer>());
  }
  
  /**
   * Checks whether there is a loop containing s. (This is sufficient since
   * there are never transitions to dead states.)
   */
  // TODO: not great that this is recursive... in theory a
  // large automata could exceed java's stack
  private static boolean isFiniteSlow(XAutomaton a, int s, HashSet<Integer> path) {
    path.add(s);
    XTransition t = new XTransition();
    int count = a.initTransition(s, t);
    for (int i=0;i<count;i++) {
      a.getNextTransition(t);
      if (path.contains(t.dest) || !isFiniteSlow(a, t.dest, path)) {
        return false;
      }
    }
    path.remove(s);
    return true;
  }
  
  /**
   * Checks that an automaton has no detached states that are unreachable
   * from the initial state.
   */
  public static void assertNoDetachedStates(XAutomaton a) {
    XAutomaton a2 = XOperations.removeDeadStates(a);
    assert a.getNumStates() == a2.getNumStates() : "automaton has " + (a.getNumStates() - a2.getNumStates()) + " detached states";
  }

  /** Returns true if the automaton is deterministic. */
  public static boolean isDeterministicSlow(XAutomaton a) {
    XTransition t = new XTransition();
    int numStates = a.getNumStates();
    for(int s=0;s<numStates;s++) {
      int count = a.initTransition(s, t);
      int lastMax = -1;
      for(int i=0;i<count;i++) {
        a.getNextTransition(t);
        if (t.min <= lastMax) {
          assert a.isDeterministic() == false;
          return false;
        }
        lastMax = t.max;
      }
    }

    assert a.isDeterministic() == true;
    return true;
  }
  
}
