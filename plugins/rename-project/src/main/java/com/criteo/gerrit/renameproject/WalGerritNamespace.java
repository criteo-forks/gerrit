// Copyright (C) 2026 Criteo
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

package com.criteo.gerrit.renameproject;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * WalGerrit's namespace, reached by reflection: the library sits in the site's {@code lib/} on
 * Gerrit's own classpath and is not a build dependency of this plugin. Resolved when the plugin
 * loads, so a site without WalGerrit refuses the plugin rather than the first rename.
 *
 * <p>With {@code plugin.rename-project.replicated} the renames received here follow renames a
 * primary made; the name references arrive by replication and WalGerrit does not rewrite them.
 */
@Singleton
class WalGerritNamespace {
  private final Object namespace;
  private final Method rename;
  private final boolean replicated;

  @Inject
  WalGerritNamespace(
      GitRepositoryManager repositories, PluginConfigFactory configs, @PluginName String pluginName) {
    this.replicated = configs.getFromGerritConfig(pluginName).getBoolean("replicated", false);
    try {
      namespace = repositories.getClass().getMethod("namespace").invoke(repositories);
      rename =
          namespace
              .getClass()
              .getMethod("rename", Project.NameKey.class, Project.NameKey.class, boolean.class);
    } catch (ReflectiveOperationException | RuntimeException missing) {
      throw new ProvisionException(
          pluginName
              + " needs WalGerrit's repository manager (gerrit.installDbModule ="
              + " dev.walgerrit.WalGitModule); found "
              + repositories.getClass().getName(),
          missing);
    }
  }

  void rename(Project.NameKey from, Project.NameKey to) throws IOException {
    try {
      rename.invoke(namespace, from, to, replicated);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof IOException io) {
        throw io;
      }
      throw new IllegalStateException(failure.getCause());
    } catch (IllegalAccessException inaccessible) {
      throw new IllegalStateException(inaccessible);
    }
  }
}
