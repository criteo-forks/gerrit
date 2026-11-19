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
package com.google.gerrit.sshd;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.entities.Account;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.Test;

public class PeerRelayTest {
  @Test
  public void theCommandKeepsTheClientsArguments() throws Exception {
    String[] arguments = {
      "--reviewer", "a b@example.com", "--cc", "o'neil", "/team \"x\"/it's\\here.git"
    };
    String command =
        PeerRelay.command(
            Account.id(1000042),
            new InetSocketAddress(InetAddress.getByName("192.0.2.7"), 50123),
            arguments);
    assertThat(CommandFactoryProvider.split(command))
        .asList()
        .containsExactly(
            "suexec",
            "--as",
            "1000042",
            "--from",
            "192.0.2.7:50123",
            "--",
            "git-receive-pack",
            "--reviewer",
            "a b@example.com",
            "--cc",
            "o'neil",
            "/team \"x\"/it's\\here.git")
        .inOrder();
  }

  @Test
  public void anIpv6ClientIsBracketed() throws Exception {
    String command =
        PeerRelay.command(
            Account.id(7),
            new InetSocketAddress(InetAddress.getByName("2001:db8::1"), 22),
            new String[] {"/p"});
    assertThat(CommandFactoryProvider.split(command))
        .asList()
        .containsExactly(
            "suexec",
            "--as",
            "7",
            "--from",
            "[2001:db8:0:0:0:0:0:1]:22",
            "--",
            "git-receive-pack",
            "/p")
        .inOrder();
  }
}
