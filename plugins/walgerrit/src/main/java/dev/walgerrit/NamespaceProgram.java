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

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/** Command-line entry point of Gerrit's {@code walgerrit-namespace} program. */
public final class NamespaceProgram {
  private static final String USAGE =
      """
      Usage: walgerrit-namespace -d SITE rename OLD NEW
             walgerrit-namespace -d SITE delete NAME
             walgerrit-namespace -d SITE pending
             walgerrit-namespace -d SITE resume OPERATION

      rename and delete run against the shared store and take effect on every node. Writers
      admitted under the old name are refused from the commit point on. A name is never reused.
      pending lists the operations no node finished; resume finishes one from any node.
      """;

  private NamespaceProgram() {}

  public static int run(GitRepositoryManager manager, String[] args) throws IOException {
    return run(manager, List.of(args), System.out);
  }

  static int run(GitRepositoryManager manager, List<String> args, PrintStream out)
      throws IOException {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
      out.print(USAGE);
      return args.isEmpty() ? 2 : 0;
    }
    if (!(manager instanceof WalGitRepositoryManager walGit)) {
      throw new IllegalStateException(
          "gerrit.installDbModule must install dev.walgerrit.WalGitModule; found "
              + manager.getClass().getName());
    }
    Namespace namespace = walGit.namespace();
    String command = args.get(0);
    List<String> operands = args.subList(1, args.size());
    switch (command) {
      case "rename" -> {
        requireOperands(command, operands, 2);
        namespace.rename(Project.nameKey(operands.get(0)), Project.nameKey(operands.get(1)));
        out.println("Renamed " + operands.get(0) + " to " + operands.get(1));
      }
      case "delete" -> {
        requireOperands(command, operands, 1);
        namespace.delete(Project.nameKey(operands.get(0)));
        out.println("Deleted " + operands.get(0));
      }
      case "pending" -> {
        requireOperands(command, operands, 0);
        for (Map.Entry<String, List<Catalog.Binding>> operation :
            namespace.pendingOperations().entrySet()) {
          for (Catalog.Binding binding : operation.getValue()) {
            out.println(
                operation.getKey() + "  " + binding.name().get() + " " + Namespace.describe(binding));
          }
        }
      }
      case "resume" -> {
        requireOperands(command, operands, 1);
        namespace.resume(operands.get(0));
        out.println("Finished " + operands.get(0));
      }
      default -> throw new IllegalArgumentException("Unknown command: " + command + "\n" + USAGE);
    }
    return 0;
  }

  private static void requireOperands(String command, List<String> operands, int count) {
    if (operands.size() != count) {
      throw new IllegalArgumentException(
          command + " takes " + count + " operand" + (count == 1 ? "" : "s") + "\n" + USAGE);
    }
  }
}
