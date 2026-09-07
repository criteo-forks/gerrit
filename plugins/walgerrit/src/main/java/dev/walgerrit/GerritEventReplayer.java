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
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.EventGson;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.proto.StorageProto.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts events another node journaled to this node's {@link EventDispatcher}, so stream-events
 * subscribers and plugins here see them exactly as the node that fired them did. The dispatcher
 * applies the usual per-listener visibility checks. {@link EventReplay} marks the thread so the
 * journal does not write the events again.
 */
@Singleton
final class GerritEventReplayer implements EventReplayer {
  private static final Logger logger = LoggerFactory.getLogger(GerritEventReplayer.class);

  private final DynamicItem<EventDispatcher> dispatcher;
  private final Gson gson;
  private final OneOffRequestContext requestContext;

  @Inject
  GerritEventReplayer(
      DynamicItem<EventDispatcher> dispatcher,
      @EventGson Gson gson,
      OneOffRequestContext requestContext) {
    this.dispatcher = dispatcher;
    this.gson = gson;
    this.requestContext = requestContext;
  }

  @Override
  public void replay(Project.NameKey project, LogEntry entry) {
    EventDispatcher target = dispatcher.get();
    if (target == null) {
      return;
    }
    try (ManualRequestContext ignored = requestContext.open();
        EventReplay.Scope replaying = EventReplay.enter()) {
      for (String json : entry.getEventJsonList()) {
        try {
          Event event = gson.fromJson(json, Event.class);
          target.postEvent(event);
        } catch (PermissionBackendException | RuntimeException failure) {
          // Events are notifications; a bad one must not stall the cursor behind it.
          logger.warn(
              "WalGerrit could not replay an event of {} journaled by {}: {}",
              project.get(),
              entry.getWriter(),
              json.length() > 200 ? json.substring(0, 200) + "..." : json,
              failure);
        }
      }
    }
  }
}
