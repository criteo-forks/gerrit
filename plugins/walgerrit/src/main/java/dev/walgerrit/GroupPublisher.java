// Copyright 2026 The WalGerrit Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.walgerrit;

import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackRef;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This node's publication pipeline for one repository, shared by every handle on the node.
 *
 * <p>Two things make a push cheaper than one manifest compare-and-swap per file it commits:
 *
 * <ul>
 *   <li><b>Deferred pack publication.</b> An object pack JGit commits before its ref transaction
 *       (the received pack, NoteDb's inserter flush) is uploaded at once but only {@linkplain
 *       #defer(Collection) held} here; the next ref transaction on this node publishes it in the
 *       same log entry, so a push costs one CAS instead of two or three. Handles on this node still
 *       see the pack through {@link #pending()}; nothing on another node can reach its objects until
 *       a ref names them, and that ref lands in the entry that carries the pack.
 *   <li><b>Group commit.</b> Ref transactions whose ref names cannot interfere are {@linkplain
 *       #admit(Set) admitted} concurrently; each validates and writes its reftable under the node
 *       lock, then hands its publication here and waits. While one publication's CAS is in flight,
 *       everything that arrives forms the next group: one log entry with every reftable, every
 *       pending pack and the concatenated ref transactions, and one CAS.
 * </ul>
 *
 * <p>Correctness never depends on the grouping. A member validated against the state this node
 * last produced; admission guarantees that every ref change this node landed since then touched
 * other names, and the {@code refRevision} fence inside {@link ManifestStore#publish} catches a
 * change from another node, failing the whole group so its members re-run against the newer
 * manifest exactly as a lone transaction does today.
 */
final class GroupPublisher {
  /** One publication a caller hands over and waits for. */
  static final class Request {
    final ManifestStore store;
    final List<PackRef> additions;
    final List<String> supersedes;
    final RefTransaction refTransaction;
    final long observedRefRevision;
    final long epoch;
    private boolean taken;
    private boolean done;
    private Manifest landed;
    private boolean shared;
    private IOException failure;

    Request(
        ManifestStore store,
        Collection<PackRef> additions,
        Collection<String> supersedes,
        RefTransaction refTransaction,
        long observedRefRevision,
        long epoch) {
      this.store = store;
      this.additions = List.copyOf(additions);
      this.supersedes = List.copyOf(supersedes);
      this.refTransaction = refTransaction;
      this.observedRefRevision = observedRefRevision;
      this.epoch = epoch;
    }

    /** A publication with nothing of its own; it exists to flush pending packs. */
    static Request flush(ManifestStore store) {
      return new Request(store, List.of(), List.of(), null, -1, -1);
    }

    boolean isRefTransaction() {
      return refTransaction != null;
    }

    boolean isCompaction() {
      return refTransaction == null && !supersedes.isEmpty();
    }
  }

  /** What a landed publication tells its member. */
  record Result(Manifest manifest, boolean shared) {}

  /** A member's claim on ref names while its transaction is validated, written and published. */
  final class Admission {
    private final Set<String> refNames;

    private Admission(Set<String> refNames) {
      this.refNames = refNames;
    }

    void release() {
      state.lock();
      try {
        inFlight.remove(this);
        changed.signalAll();
      } finally {
        state.unlock();
      }
    }
  }

  private final ReentrantLock nodeLock = new ReentrantLock();
  private final ReentrantLock state = new ReentrantLock();
  private final Condition changed = state.newCondition();
  private final Deque<Request> queue = new ArrayDeque<>();
  private final List<Admission> inFlight = new ArrayList<>();
  private final Map<String, PackRef> pendingAdditions = new LinkedHashMap<>();
  private boolean publishing;
  private long epoch;
  private long knownRefRevision = -1;

  /** Serializes this node's ref transactions on the repository while they validate and write. */
  ReentrantLock nodeLock() {
    return nodeLock;
  }

  /**
   * Waits until no transaction in flight on this node touches a name that could interfere with
   * {@code refNames}: the same ref, or one nested under the other, which JGit rejects as a name
   * conflict. Returns the claim to {@linkplain Admission#release() release} when the transaction is
   * over, whichever way it ended.
   */
  Admission admit(Set<String> refNames) throws IOException {
    state.lock();
    try {
      while (conflicts(refNames)) {
        changed.await();
      }
      Admission admission = new Admission(Set.copyOf(refNames));
      inFlight.add(admission);
      return admission;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting to start a ref transaction", interrupted);
    } finally {
      state.unlock();
    }
  }

  /** Whether the calling member is the only transaction in flight on this node. */
  boolean alone() {
    state.lock();
    try {
      return inFlight.size() <= 1;
    } finally {
      state.unlock();
    }
  }

  /**
   * Records the ref revision a transaction validated against and returns its validation epoch. A
   * revision this node did not itself publish means another node changed refs; that starts a new
   * epoch, and members of the previous one are failed instead of published on top of a state they
   * never saw.
   */
  long observe(long refRevision) {
    state.lock();
    try {
      if (refRevision > knownRefRevision) {
        knownRefRevision = refRevision;
        epoch++;
      }
      return epoch;
    } finally {
      state.unlock();
    }
  }


  /** Holds uploaded packs for the next publication on this node. */
  void defer(Collection<PackRef> additions) {
    state.lock();
    try {
      for (PackRef addition : additions) {
        pendingAdditions.putIfAbsent(addition.getName(), addition);
      }
    } finally {
      state.unlock();
    }
  }

  /** Packs uploaded on this node that no manifest lists yet, in commit order. */
  List<PackRef> pending() {
    state.lock();
    try {
      return List.copyOf(pendingAdditions.values());
    } finally {
      state.unlock();
    }
  }

  boolean hasPending() {
    state.lock();
    try {
      return !pendingAdditions.isEmpty();
    } finally {
      state.unlock();
    }
  }

  /** Whether any of the named packs still waits for a publication. */
  boolean hasPendingAny(Collection<String> names) {
    state.lock();
    try {
      for (String name : names) {
        if (pendingAdditions.containsKey(name)) {
          return true;
        }
      }
      return false;
    } finally {
      state.unlock();
    }
  }

  /** Names of the pending packs. */
  Set<String> pendingNames() {
    state.lock();
    try {
      return Set.copyOf(pendingAdditions.keySet());
    } finally {
      state.unlock();
    }
  }

  /** File names of the pending packs; the reclaimer must not treat them as unreferenced. */
  Set<String> pendingFileNames() {
    return ManifestStore.fileNames(pending());
  }

  /**
   * Publishes the request together with everything else waiting, and returns once its entry is in
   * the manifest. The thread that finds no publication in flight publishes the group itself, so an
   * idle repository pays nothing for the grouping and a busy one forms groups as large as one CAS
   * round trip lets accumulate. Throws what {@link ManifestStore#publish} would have thrown for
   * this request alone.
   */
  Result publish(Request request) throws IOException {
    Admission placeholder = null;
    state.lock();
    try {
      if (request.isCompaction()) {
        // A compaction supersedes tables members might otherwise fold into their own; while it is
        // queued, members see that they are not alone and extend the stack instead.
        placeholder = new Admission(Set.of());
        inFlight.add(placeholder);
      }
      queue.addLast(request);
      while (!request.done) {
        if (!publishing) {
          publishing = true;
          List<Request> group = new ArrayList<>(queue);
          queue.clear();
          for (Request member : group) {
            member.taken = true;
          }
          state.unlock();
          try {
            publishGroup(group);
          } finally {
            state.lock();
            publishing = false;
            changed.signalAll();
          }
          continue;
        }
        awaitChange(request);
      }
      if (request.failure != null) {
        throw request.failure;
      }
      return new Result(request.landed, request.shared);
    } finally {
      if (placeholder != null) {
        inFlight.remove(placeholder);
        changed.signalAll();
      }
      state.unlock();
    }
  }

  /** Waits for a state change; a request already taken by a publisher cannot be abandoned. */
  private void awaitChange(Request request) throws IOException {
    try {
      changed.await();
    } catch (InterruptedException interrupted) {
      if (!request.taken) {
        queue.remove(request);
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while waiting to publish", interrupted);
      }
      while (!request.done) {
        changed.awaitUninterruptibly();
      }
      Thread.currentThread().interrupt();
    }
  }

  private void publishGroup(List<Request> group) {
    List<PackRef> drained;
    long groupEpoch;
    long expectedRefRevision;
    state.lock();
    try {
      drained = new ArrayList<>(pendingAdditions.values());
      pendingAdditions.clear();
      groupEpoch = epoch;
      expectedRefRevision = knownRefRevision;
    } finally {
      state.unlock();
    }
    List<Request> accepted = new ArrayList<>();
    List<PackRef> additions = new ArrayList<>(drained);
    List<String> supersedes = new ArrayList<>();
    RefTransaction.Builder transaction = RefTransaction.newBuilder();
    boolean refUpdate = false;
    try {
      ManifestStore store = group.get(0).store;
      Set<String> pendingNames = new HashSet<>();
      for (PackRef pack : drained) {
        pendingNames.add(pack.getName());
      }
      boolean supersedesPending =
          group.stream().anyMatch(member -> member.supersedes.stream().anyMatch(pendingNames::contains));
      if (supersedesPending) {
        // A compaction of packs no manifest lists yet (JGit's compactor run over a handle's own
        // flushes): list them first, so an entry only ever supersedes what was live.
        store.publish(0, drained, List.of(), false, null);
        drained = List.of();
        additions.clear();
      }
      Set<String> live = new HashSet<>();
      for (PackRef pack : store.current().getPacksList()) {
        live.add(pack.getName());
      }
      Set<String> superseded = new HashSet<>();
      for (Request member : group) {
        IOException rejection = rejection(member, groupEpoch, expectedRefRevision, live, superseded);
        if (rejection != null) {
          complete(List.of(member), null, false, rejection);
          continue;
        }
        superseded.addAll(member.supersedes);
        accepted.add(member);
        additions.addAll(member.additions);
        supersedes.addAll(member.supersedes);
        if (member.isRefTransaction()) {
          refUpdate = true;
          transaction.addAllUpdates(member.refTransaction.getUpdatesList());
        }
      }
      if (accepted.isEmpty()) {
        defer(drained);
        return;
      }
      if (additions.isEmpty() && supersedes.isEmpty() && !refUpdate) {
        // Nothing to publish: flush requests that found the pending packs already gone.
        complete(accepted, store.current(), false, null);
        return;
      }
      Manifest updated =
          store.publish(
              refUpdate ? expectedRefRevision : 0,
              additions,
              supersedes,
              refUpdate,
              refUpdate ? transaction.build() : null);
      complete(accepted, updated, accepted.size() > 1 || !drained.isEmpty(), null);
    } catch (IOException failure) {
      defer(drained);
      if (failure instanceof ManifestConflictException) {
        // The store answered with a manifest whose refs this node did not write.
        try {
          observe(group.get(0).store.current().getRefRevision());
        } catch (IOException ignored) {
          // The members re-read the manifest when they re-run.
        }
      }
      complete(accepted, null, false, failure);
    } catch (RuntimeException failure) {
      defer(drained);
      complete(accepted, null, false, new IOException("Publication failed", failure));
      throw failure;
    }
  }

  private static IOException rejection(
      Request member,
      long groupEpoch,
      long expectedRefRevision,
      Set<String> live,
      Set<String> alreadySuperseded) {
    if (member.isRefTransaction() && member.epoch != groupEpoch) {
      return new ManifestConflictException(member.observedRefRevision, expectedRefRevision);
    }
    for (String name : member.supersedes) {
      if (!live.contains(name) || alreadySuperseded.contains(name)) {
        return new StaleCompactionInputException(name);
      }
    }
    return null;
  }

  private void complete(List<Request> members, Manifest landed, boolean shared, IOException failure) {
    state.lock();
    try {
      if (landed != null) {
        knownRefRevision = Math.max(knownRefRevision, landed.getRefRevision());
      }
      for (Request member : members) {
        member.landed = landed;
        member.shared = shared;
        member.failure = failure;
        member.done = true;
      }
      changed.signalAll();
    } finally {
      state.unlock();
    }
  }

  private boolean conflicts(Set<String> refNames) {
    for (Admission admission : inFlight) {
      for (String held : admission.refNames) {
        for (String wanted : refNames) {
          if (namesConflict(held, wanted)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** Same ref, or one would be a directory the other lives in: Git allows neither together. */
  static boolean namesConflict(String a, String b) {
    return a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/");
  }
}
