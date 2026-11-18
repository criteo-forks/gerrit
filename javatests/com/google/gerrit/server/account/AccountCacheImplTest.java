// Copyright (C) 2026 The Android Open Source Project
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

package com.google.gerrit.server.account;

import static com.google.common.truth.Truth8.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.ExternalIdKeyFactory;
import com.google.gerrit.server.account.externalids.storage.notedb.ExternalIdsNoteDbImpl;
import java.io.IOException;
import java.util.Optional;
import org.junit.Test;

public class AccountCacheImplTest {
  @Test
  public void authenticationCanDistinguishAnUnknownUserFromAnUnreadableExternalId()
      throws Exception {
    ExternalIdsNoteDbImpl ids = mock(ExternalIdsNoteDbImpl.class);
    ExternalIdKeyFactory keys = mock(ExternalIdKeyFactory.class);
    ExternalId.Key key = ExternalIdKeyFactory.parse("username:alice", false);
    when(keys.create(ExternalId.SCHEME_USERNAME, "alice")).thenReturn(key);
    AccountCacheImpl accounts = new AccountCacheImpl(ids, null, null, null, null, keys);

    when(ids.get(key)).thenReturn(Optional.empty()).thenThrow(new IOException("store unavailable"));
    assertThat(accounts.getByUsernameForAuthentication("alice")).isEmpty();
    assertThrows(StorageException.class, () -> accounts.getByUsernameForAuthentication("alice"));
    assertThat(accounts.getByUsername("alice")).isEmpty();
  }
}
