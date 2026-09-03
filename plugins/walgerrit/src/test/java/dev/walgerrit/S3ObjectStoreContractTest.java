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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.walgerrit.proto.StorageProto.PackFile;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Store-contract tests that run against MinIO/RustFS when an endpoint is supplied. */
class S3ObjectStoreContractTest {
  private static final String ENDPOINT_ENV = "WALGERRIT_S3_TEST_ENDPOINT";
  private static final String BUCKET_ENV = "WALGERRIT_S3_TEST_BUCKET";

  private static S3Client administration;
  private static S3ObjectStore store;
  private static String bucket;

  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void start() {
    String endpoint = System.getenv(ENDPOINT_ENV);
    Assumptions.assumeTrue(endpoint != null && !endpoint.isBlank(), ENDPOINT_ENV + " is not set");
    bucket = System.getenv().getOrDefault(BUCKET_ENV, "walgerrit-test");
    administration =
        S3Client.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClient(Apache5HttpClient.builder().build())
            .endpointOverride(URI.create(endpoint))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    try {
      administration.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    } catch (S3Exception exception) {
      if (exception.statusCode() != 409) {
        throw exception;
      }
    }
    store = new S3ObjectStore(bucket, "us-east-1", URI.create(endpoint), true);
  }

  @AfterAll
  static void stop() {
    if (store != null) {
      store.close();
    }
    if (administration != null) {
      administration.close();
    }
  }

  @Test
  void supportsCreateCasAndImmutableFileRoundTrip() throws Exception {
    String prefix = "contract/" + UUID.randomUUID() + "/";
    ObjectStore scoped = new PrefixedObjectStore(store, prefix);

    ObjectStore.StoredObject created = scoped.putIfAbsent("manifest", new byte[] {1});
    assertThrows(
        ObjectAlreadyExistsException.class,
        () -> scoped.putIfAbsent("manifest", new byte[] {2}));

    ObjectStore.StoredObject updated =
        scoped.compareAndSwap("manifest", created.version(), new byte[] {2});
    assertArrayEquals(new byte[] {2}, scoped.get("manifest").orElseThrow().bytes());
    assertThrows(
        ObjectStoreConflictException.class,
        () -> scoped.compareAndSwap("manifest", created.version(), new byte[] {3}));
    assertTrue(!updated.version().equals(created.version()));

    Path source = temporaryDirectory.resolve("source.pack");
    Files.write(source, new byte[] {4, 5, 6});
    scoped.uploadIfAbsent("wal/pack.pack", source);
    scoped.uploadIfAbsent("wal/pack.pack", source);
    Path target = temporaryDirectory.resolve("cache/pack.pack");
    scoped.download("wal/pack.pack", target);
    assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(target));
  }

  @Test
  void manifestCasHasOneRefWinnerAcrossTwoNodeCaches() throws Exception {
    String prefix = "manifest/" + UUID.randomUUID() + "/repo.git/";
    ObjectStore scoped = new PrefixedObjectStore(store, prefix);
    ManifestStore first =
        new ManifestStore(scoped, temporaryDirectory.resolve("node-1/repo.git"), "repo");
    ManifestStore second =
        new ManifestStore(scoped, temporaryDirectory.resolve("node-2/repo.git"), "repo");
    assertTrue(first.create());

    try (var executor = Executors.newFixedThreadPool(2)) {
      var results =
          executor.invokeAll(
              List.of(
                  () -> first.publish(0, List.of(reftable("refs-1")), List.of(), true),
                  () -> second.publish(0, List.of(reftable("refs-2")), List.of(), true)));
      int winners = 0;
      int conflicts = 0;
      for (var result : results) {
        try {
          result.get();
          winners++;
        } catch (ExecutionException exception) {
          if (exception.getCause() instanceof ManifestConflictException) {
            conflicts++;
          } else {
            throw exception;
          }
        }
      }
      assertEquals(1, winners);
      assertEquals(1, conflicts);
    }
    assertEquals(1, first.read().getRefRevision());
    assertEquals(1, second.read().getRefRevision());
  }

  @Test
  void conditionalReadsAnswerUnchangedWithoutABody() throws Exception {
    String prefix = "conditional/" + UUID.randomUUID() + "/";
    ObjectStore scoped = new PrefixedObjectStore(store, prefix);

    assertEquals(
        ObjectStore.ConditionalRead.State.ABSENT,
        scoped.getIfChanged("manifest", null).state());
    ObjectStore.StoredObject created = scoped.putIfAbsent("manifest", new byte[] {1});
    assertEquals(
        ObjectStore.ConditionalRead.State.UNCHANGED,
        scoped.getIfChanged("manifest", created.version()).state());

    ObjectStore.StoredObject updated =
        scoped.compareAndSwap("manifest", created.version(), new byte[] {2});
    ObjectStore.ConditionalRead changed = scoped.getIfChanged("manifest", created.version());
    assertEquals(ObjectStore.ConditionalRead.State.CHANGED, changed.state());
    assertArrayEquals(new byte[] {2}, changed.object().bytes());
    assertEquals(updated.version(), changed.object().version());
    assertEquals(
        ObjectStore.ConditionalRead.State.UNCHANGED,
        scoped.getIfChanged("manifest", updated.version()).state());
  }

  @Test
  void listingReportsTheSameVersionAConditionalWriteReturned() throws Exception {
    String prefix = "listing/" + UUID.randomUUID() + "/";
    ObjectStore scoped = new PrefixedObjectStore(store, prefix);
    ObjectStore.StoredObject created = scoped.putIfAbsent("manifests/a.git/manifest.pb", new byte[] {1});
    ObjectStore.StoredObject updated =
        scoped.compareAndSwap("manifests/a.git/manifest.pb", created.version(), new byte[] {2});
    scoped.putIfAbsent("repos/a.git/log/1.pb", new byte[] {3});

    List<ObjectStore.ObjectSummary> listed = scoped.listWithVersions("manifests/");

    assertEquals(1, listed.size());
    assertEquals("manifests/a.git/manifest.pb", listed.get(0).key());
    assertEquals(updated.version(), listed.get(0).version());
    assertEquals(
        ObjectStore.ConditionalRead.State.UNCHANGED,
        scoped.getIfChanged("manifests/a.git/manifest.pb", listed.get(0).version()).state());
  }

  private static PackRef reftable(String name) {
    return PackRef.newBuilder()
        .setName(name)
        .setSource("INSERT")
        .addFiles(PackFile.newBuilder().setExtension("ref"))
        .build();
  }

  @Test
  void largeFilesGoThroughMultipartUploadWithTheSameSemantics() throws Exception {
    String prefix = "contract/" + UUID.randomUUID() + "/";
    ObjectStore scoped = new PrefixedObjectStore(store, prefix);
    store.setMultipartThresholdForTesting(1L << 20);
    try {
      byte[] content = new byte[(int) (S3ObjectStore.PART_SIZE * 2 + 12345)];
      new java.util.Random(7).nextBytes(content);
      Path source = temporaryDirectory.resolve("large.pack");
      Files.write(source, content);

      scoped.uploadIfAbsent("wal/large.pack", source);
      scoped.uploadIfAbsent("wal/large.pack", source);

      Path downloaded = temporaryDirectory.resolve("large.downloaded");
      scoped.download("wal/large.pack", downloaded);
      assertArrayEquals(content, Files.readAllBytes(downloaded));
      assertTrue(scoped.list("wal/").contains("wal/large.pack"));
    } finally {
      store.setMultipartThresholdForTesting(S3ObjectStore.DEFAULT_MULTIPART_THRESHOLD);
    }
  }
}
