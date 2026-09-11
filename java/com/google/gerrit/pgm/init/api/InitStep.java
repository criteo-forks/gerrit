// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.pgm.init.api;

/** A single step in the site initialization process. */
public interface InitStep {
  void run() throws Exception;

  /**
   * Executed after the schema has been initialized or upgraded.
   *
   * <p>Repository access belongs here, when {@link GitRepositoryManagerOnInit} uses the configured
   * repository manager. During {@link #run()}, that manager is not yet available.
   */
  default void postRun() throws Exception {}
}
