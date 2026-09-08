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
  private final Map<String, PendingPack> pendingAdditions = new LinkedHashMap<>();

  /**
   * Pending packs a publication in flight carries; still listed and protected until it resolves.
   */
  private final Map<String, PendingPack> inFlightAdditions = new LinkedHashMap<>();

  /** Publications whose outcome is unknown, with the packs they carried; settled from the log. */
  private final List<Uncertain> uncertain = new ArrayList<>();

  private record Attempt(long sequence, String transactionId) {}

  /**
   * Shared with read snapshots even after it leaves the inventory. Registering the attempt before
   * its CAS lets a reader exclude a pack already committed and retired while the reply is in
   * flight.
   */
  private static final class PendingPack {
    final PackRef pack;
    volatile Attempt attempt;

    PendingPack(PackRef pack) {
      this.pack = pack;
    }
  }

  /** A lost-response publication: which transaction, and which pending packs rode in it. */
  private record Uncertain(long sequence, String transactionId, Set<String> packNames) {}
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
        pendingAdditions.putIfAbsent(addition.getName(), new PendingPack(addition));
      }
    } finally {
      state.unlock();
    }
  }

  /**
   * Packs uploaded on this node that no manifest is known to list yet, in commit order: those
   * waiting for a publication and those a publication in flight is carrying.
   */
  List<PackRef> pending() {
    state.lock();
    try {
      List<PendingPack> packs = new ArrayList<>(inFlightAdditions.values());
      packs.addAll(pendingAdditions.values());
      return packs.stream().map(pending -> pending.pack).toList();
    } finally {
      state.unlock();
    }
  }

  /** A read view sampled in publication order: unpublished state first, then the manifest. */
  record Snapshot(Manifest manifest, List<PackRef> unpublished) {}

  Snapshot snapshot(ManifestStore store, boolean refresh) throws IOException {
    List<PendingPack> packs;
    state.lock();
    try {
      packs = new ArrayList<>(inFlightAdditions.values());
      packs.addAll(pendingAdditions.values());
    } finally {
      state.unlock();
    }
    Manifest manifest = refresh ? store.refresh() : store.current();
    Map<Attempt, Boolean> outcomes = new LinkedHashMap<>();
    List<PackRef> unpublished = new ArrayList<>();
    for (PendingPack pending : packs) {
      // Read the attempt after the manifest. A CAS registered after this inventory snapshot may
      // already have landed by the manifest read. The shared record survives inventory removal.
      Attempt attempt = pending.attempt;
      boolean landed = false;
      if (attempt != null && manifest.getHeadSeq() >= attempt.sequence()) {
        Boolean known = outcomes.get(attempt);
        if (known == null) {
          known = store.transactionLanded(manifest, attempt.sequence(), attempt.transactionId());
          outcomes.put(attempt, known);
        }
        landed = known;
      }
      if (!landed) {
        unpublished.add(pending.pack);
      }
    }
    return new Snapshot(manifest, List.copyOf(unpublished));
  }

  boolean hasPending() {
    state.lock();
    try {
      return !pendingAdditions.isEmpty() || !inFlightAdditions.isEmpty();
    } finally {
      state.unlock();
    }
  }

  /** Whether any of the named packs still waits for a publication to resolve. */
  boolean hasPendingAny(Collection<String> names) {
    state.lock();
    try {
      for (String name : names) {
        if (pendingAdditions.containsKey(name) || inFlightAdditions.containsKey(name)) {
          return true;
        }
      }
      return false;
    } finally {
      state.unlock();
    }
  }

  /** Requests waiting for a publisher; lets tests wait for a transaction to queue. */
  int queuedForTesting() {
    state.lock();
    try {
      return queue.size();
    } finally {
      state.unlock();
    }
  }

  /** Names of every pack {@link #pending()} lists. */
  Set<String> pendingNames() {
    state.lock();
    try {
      Set<String> names = new HashSet<>(inFlightAdditions.keySet());
      names.addAll(pendingAdditions.keySet());
      return Set.copyOf(names);
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
    List<PackRef> drained = List.of();
    long expectedRefRevision = -1;
    List<Request> accepted = new ArrayList<>();
    try {
      ManifestStore store = group.get(0).store;
      // Finish recovery before moving packs into this group. Partial recovery can retire an
      // earlier pack without a later failure putting it back into the pending inventory.
      settleUncertain(store);
      long groupEpoch;
      state.lock();
      try {
        drained = pendingAdditions.values().stream().map(pending -> pending.pack).toList();
        inFlightAdditions.putAll(pendingAdditions);
        pendingAdditions.clear();
        groupEpoch = epoch;
        expectedRefRevision = knownRefRevision;
      } finally {
        state.unlock();
      }
      List<PackRef> additions = new ArrayList<>(drained);
      List<String> supersedes = new ArrayList<>();
      RefTransaction.Builder transaction = RefTransaction.newBuilder();
      boolean refUpdate = false;
      Set<String> pendingNames = new HashSet<>();
      for (PackRef pack : drained) {
        pendingNames.add(pack.getName());
      }
      boolean supersedesPending =
          group.stream().anyMatch(member -> member.supersedes.stream().anyMatch(pendingNames::contains));
      if (supersedesPending) {
        // A compaction of packs no manifest lists yet (JGit's compactor run over a handle's own
        // flushes): list them first, so an entry only ever supersedes what was live.
        publishCarrying(store, 0, drained, List.of(), false, null, drained);
        settle(drained, true);
        drained = List.of();
        additions.clear();
      }
      Manifest before = store.current();
      Set<String> live = new HashSet<>();
      for (PackRef pack : before.getPacksList()) {
        live.add(pack.getName());
      }
      Set<String> superseded = new HashSet<>();
      for (Request member : group) {
        IOException rejection = rejection(member, groupEpoch, expectedRefRevision, live, superseded);
        if (rejection != null) {
          complete(List.of(member), null, false, expectedRefRevision, rejection);
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
        settle(drained, false);
        return;
      }
      if (additions.isEmpty() && supersedes.isEmpty() && !refUpdate) {
        // Nothing to publish: flush requests that found the pending packs already gone.
        complete(accepted, before, false, expectedRefRevision, null);
        return;
      }
      // What this publication itself does to the ref revision; anything beyond it in the manifest
      // that comes back was written by another node after members validated.
      long produced =
          expectedRefRevision + (ManifestStore.changesRefs(additions, supersedes, before) ? 1 : 0);
      Manifest updated =
          publishCarrying(
              store,
              refUpdate ? expectedRefRevision : 0,
              additions,
              supersedes,
              refUpdate,
              refUpdate ? transaction.build() : null,
              drained);
      settle(drained, true);
      complete(accepted, updated, accepted.size() > 1 || !drained.isEmpty(), produced, null);
    } catch (IOException failure) {
      settle(drained, false);
      if (failure instanceof ManifestConflictException) {
        // The store answered with a manifest whose refs this node did not write.
        try {
          observe(group.get(0).store.current().getRefRevision());
        } catch (IOException ignored) {
          // The members re-read the manifest when they re-run.
        }
      }
      // Recovery and pre-publication uploads can fail before accepted is populated. Every
      // dequeued request must finish, including a waiter interrupted after it was taken.
      complete(group, null, false, expectedRefRevision, failure);
    } catch (RuntimeException failure) {
      settle(drained, false);
      complete(
          group, null, false, expectedRefRevision, new IOException("Publication failed", failure));
    }
  }

  private Manifest publishCarrying(
      ManifestStore store,
      long expectedRefRevision,
      Collection<PackRef> additions,
      Collection<String> supersedes,
      boolean refUpdate,
      RefTransaction transaction,
      List<PackRef> carried)
      throws IOException {
    try {
      return store.publish(
          expectedRefRevision,
          additions,
          supersedes,
          refUpdate,
          transaction,
          (sequence, transactionId) -> {
            Attempt attempt = new Attempt(sequence, transactionId);
            state.lock();
            try {
              for (PackRef pack : carried) {
                inFlightAdditions.get(pack.getName()).attempt = attempt;
              }
            } finally {
              state.unlock();
            }
          });
    } catch (AmbiguousPublicationException unknown) {
      // Associate packs only with their own attempted publication, never with a recovery fence.
      remember(unknown, carried);
      throw unknown;
    }
  }

  private void remember(AmbiguousPublicationException unknown, List<PackRef> carried) {
    Set<String> names = new HashSet<>();
    for (PackRef pack : carried) {
      names.add(pack.getName());
    }
    state.lock();
    try {
      uncertain.add(new Uncertain(unknown.sequence(), unknown.transactionId(), names));
    } finally {
      state.unlock();
    }
  }

  /**
   * Settles unknown publications from the log, fencing any attempt whose sequence is not yet
   * occupied. The record is removed only after a definitive outcome. A recovery read or fence
   * failure leaves both the record and its packs available for the next attempt.
   */
  private void settleUncertain(ManifestStore store) throws IOException {
    List<Uncertain> unsettled;
    state.lock();
    try {
      if (uncertain.isEmpty()) {
        return;
      }
      unsettled = new ArrayList<>(uncertain);
    } finally {
      state.unlock();
    }
    for (Uncertain publication : unsettled) {
      ManifestStore.Resolution resolution =
          store.resolvePublication(publication.sequence(), publication.transactionId());
      state.lock();
      try {
        if (resolution.landed()) {
          forget(publication.packNames());
        }
        uncertain.remove(publication);
        // Ref changes seen during recovery invalidate candidates validated before that outcome.
        observe(resolution.manifest().getRefRevision());
      } finally {
        state.unlock();
      }
    }
  }

  /** Drops packs from every node-local list: they are in a manifest, past or present. */
  private void forget(Set<String> names) {
    state.lock();
    try {
      for (String name : names) {
        pendingAdditions.remove(name);
        inFlightAdditions.remove(name);
      }
    } finally {
      state.unlock();
    }
  }

  /** Takes the group's packs out of the in-flight set: published, or back to pending. */
  private void settle(List<PackRef> drained, boolean published) {
    state.lock();
    try {
      for (PackRef pack : drained) {
        PendingPack pending = inFlightAdditions.remove(pack.getName());
        if (!published && pending != null) {
          pendingAdditions.putIfAbsent(pack.getName(), pending);
        }
      }
    } finally {
      state.unlock();
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

  /**
   * Hands the outcome to the members. A landed manifest whose ref revision exceeds what this
   * publication produced (a lost CAS response recovered from a later read) carries another node's
   * ref changes, so the validation epoch advances and queued members re-run against them.
   */
  private void complete(
      List<Request> members,
      Manifest landed,
      boolean shared,
      long producedRefRevision,
      IOException failure) {
    state.lock();
    try {
      if (landed != null) {
        if (landed.getRefRevision() > producedRefRevision) {
          epoch++;
        }
        knownRefRevision = Math.max(knownRefRevision, landed.getRefRevision());
      }
      for (Request member : members) {
        if (member.done) {
          continue;
        }
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
