// Copyright 2026 The WalGerrit Authors
// SPDX-License-Identifier: Apache-2.0

package dev.walgerrit;

import com.google.common.primitives.Ints;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.RefNames;
import dev.walgerrit.proto.StorageProto.AccountRefPage;
import dev.walgerrit.proto.StorageProto.AuthRefTip;
import dev.walgerrit.proto.StorageProto.AuthRefs;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jgit.internal.storage.reftable.MergedReftable;
import org.eclipse.jgit.internal.storage.reftable.RefCursor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;

/** Exact direct refs used by All-Users authentication, at one manifest ref revision. */
final class AuthRefDirectory {
  private static final int OTHER = -1;
  private static final int EXTERNAL_IDS = -2;
  private static final int DEFAULT_PREFERENCES = -3;

  private final ManifestStore store;
  private final AuthRefs.Builder directory;
  private final Map<Integer, AccountRefPage.Builder> dirty = new HashMap<>();

  private AuthRefDirectory(ManifestStore store, AuthRefs base) {
    this.store = store;
    directory = base.toBuilder();
  }

  static boolean complete(Manifest manifest) {
    return manifest.hasAuthRefs()
        && manifest.getAuthRefs().getRefRevision() == manifest.getRefRevision();
  }

  static boolean covers(String name) {
    return key(name) != OTHER;
  }

  private static int key(String name) {
    if (name.equals(RefNames.REFS_EXTERNAL_IDS)) {
      return EXTERNAL_IDS;
    }
    if (name.equals(RefNames.REFS_USERS_DEFAULT)) {
      return DEFAULT_PREFERENCES;
    }
    int start = RefNames.REFS_USERS.length() + 3;
    if (!name.startsWith(RefNames.REFS_USERS)
        || name.length() <= start
        || name.length() > start + 10
        || name.charAt(start - 1) != '/') {
      return OTHER;
    }
    Integer id = Ints.tryParse(name.substring(start));
    return id != null && id >= 0 && name.equals(RefNames.refsUsers(Account.id(id))) ? id : OTHER;
  }

  // Each page covers 256 consecutive account IDs.
  private static int pageNumber(int id) {
    return id >>> 8;
  }

  interface Lookup {
    Ref exactRef(String name) throws IOException;
  }

  static Ref exactRef(Manifest manifest, ManifestStore store, String name, Lookup fallback)
      throws IOException {
    if (!complete(manifest)) {
      return fallback.exactRef(name);
    }
    AuthRefs directory = manifest.getAuthRefs();
    AuthRefTip tip;
    int id = key(name);
    switch (id) {
      case OTHER -> {
        return fallback.exactRef(name);
      }
      case EXTERNAL_IDS -> tip = directory.hasExternalIds() ? directory.getExternalIds() : null;
      case DEFAULT_PREFERENCES ->
          tip = directory.hasDefaultPreferences() ? directory.getDefaultPreferences() : null;
      default -> {
        String pointer = directory.getPagesMap().get(pageNumber(id));
        if (pointer == null) {
          return null;
        }
        AccountRefPage page = store.accountPage(pointer);
        if (page == null) {
          return fallback.exactRef(name);
        }
        tip = page.getTipsMap().get(id);
      }
    }
    if (tip == null) {
      return null;
    }
    if (tip.getObjectId().isEmpty()) {
      return fallback.exactRef(name);
    }
    ObjectId oid = ObjectId.fromString(tip.getObjectId());
    return tip.getPeeledObjectId().isEmpty()
        ? new ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED, name, oid, tip.getUpdateIndex())
        : new ObjectIdRef.PeeledTag(
            Ref.Storage.PACKED,
            name,
            oid,
            ObjectId.fromString(tip.getPeeledObjectId()),
            tip.getUpdateIndex());
  }

  record Seed(long refRevision, Map<Integer, AuthRefTip> tips) {}

  /** Scan raw refs so even a dangling symbolic ref gets a fallback marker. */
  static Seed scan(long refRevision, MergedReftable table) throws IOException {
    Map<Integer, AuthRefTip> tips = new HashMap<>();
    Ref external = table.exactRef(RefNames.REFS_EXTERNAL_IDS);
    if (external != null) {
      tips.put(EXTERNAL_IDS, tip(external));
    }
    try (RefCursor cursor = table.seekRefsWithPrefix(RefNames.REFS_USERS)) {
      while (cursor.next()) {
        Ref ref = cursor.getRef();
        int id = key(ref.getName());
        if (id != OTHER) {
          tips.put(id, tip(ref));
        }
      }
    }
    return new Seed(refRevision, tips);
  }

  private static AuthRefTip tip(Ref ref) {
    if (ref.isSymbolic()) {
      return AuthRefTip.getDefaultInstance();
    }
    AuthRefTip.Builder tip =
        AuthRefTip.newBuilder()
            .setObjectId(ref.getObjectId().name())
            .setUpdateIndex(ref.getUpdateIndex());
    if (ref.getPeeledObjectId() != null) {
      tip.setPeeledObjectId(ref.getPeeledObjectId().name());
    }
    return tip.build();
  }

  static void publish(
      ManifestStore store,
      Manifest base,
      Seed seed,
      RefTransaction transaction,
      LogEntry.Kind kind,
      Manifest.Builder next)
      throws IOException {
    next.clearAuthRefs();
    if (transaction == null) {
      if (kind == LogEntry.Kind.COMPACT && complete(base)) {
        next.setAuthRefs(base.getAuthRefs().toBuilder().setRefRevision(next.getRefRevision()));
      }
      return;
    }

    AuthRefDirectory editor;
    if (complete(base)) {
      editor = new AuthRefDirectory(store, base.getAuthRefs());
    } else if (seed != null && seed.refRevision == base.getRefRevision()) {
      editor = new AuthRefDirectory(store, AuthRefs.getDefaultInstance());
      for (var entry : seed.tips.entrySet()) {
        editor.set(entry.getKey(), entry.getValue());
      }
    } else {
      return;
    }
    for (RefUpdate update : transaction.getUpdatesList()) {
      int id = key(update.getName());
      if (id == OTHER) {
        continue;
      }
      AuthRefTip tip = null;
      if (!update.getNewSymbolicTarget().isEmpty()) {
        tip = AuthRefTip.getDefaultInstance();
      } else if (!ObjectId.zeroId().name().equals(update.getNewObjectId())) {
        if (!update.hasAuthUpdateIndex()) {
          return;
        }
        tip =
            AuthRefTip.newBuilder()
                .setObjectId(update.getNewObjectId())
                .setUpdateIndex(update.getAuthUpdateIndex())
                .setPeeledObjectId(update.getPeeledObjectId())
                .build();
      }
      if (!editor.set(id, tip)) {
        return;
      }
    }
    for (var entry : editor.dirty.entrySet()) {
      AccountRefPage page = entry.getValue().build();
      if (page.getTipsCount() == 0) {
        editor.directory.removePages(entry.getKey());
      } else {
        editor.directory.putPages(entry.getKey(), store.writeAccountPage(page));
      }
    }
    next.setAuthRefs(editor.directory.setRefRevision(next.getRefRevision()));
  }

  private boolean set(int id, AuthRefTip tip) throws IOException {
    if (id == EXTERNAL_IDS) {
      if (tip == null) {
        directory.clearExternalIds();
      } else {
        directory.setExternalIds(tip);
      }
    } else if (id == DEFAULT_PREFERENCES) {
      if (tip == null) {
        directory.clearDefaultPreferences();
      } else {
        directory.setDefaultPreferences(tip);
      }
    } else {
      int number = pageNumber(id);
      AccountRefPage.Builder page = dirty.get(number);
      if (page == null) {
        String pointer = directory.getPagesMap().get(number);
        AccountRefPage base =
            pointer == null ? AccountRefPage.getDefaultInstance() : store.accountPage(pointer);
        if (base == null) {
          return false;
        }
        page = base.toBuilder();
        dirty.put(number, page);
      }
      if (tip == null) {
        page.removeTips(id);
      } else {
        page.putTips(id, tip);
      }
    }
    return true;
  }
}
