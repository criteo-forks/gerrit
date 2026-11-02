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

package com.google.gerrit.server.index.change;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.gerrit.extensions.events.ChangeIndexedListener;
import com.google.gerrit.server.index.options.IsFirstInsertForEntry;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import org.eclipse.jgit.lib.Config;

/** Lets WalGerrit's tests subclass ChangeIndexer, whose constructors are package-private. */
public class TestChangeIndexer extends ChangeIndexer {
  public TestChangeIndexer() {
    super(
        new Config(),
        (ChangeData.Factory) null,
        (ChangeNotes.Factory) null,
        (ThreadLocalRequestContext) null,
        (PluginSetContext<ChangeIndexedListener>) null,
        (StalenessChecker) null,
        (ListeningExecutorService) null,
        (ListeningExecutorService) null,
        (ChangeIndex) null,
        false,
        (IsFirstInsertForEntry) null);
  }
}
