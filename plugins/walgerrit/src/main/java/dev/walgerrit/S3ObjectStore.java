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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/** S3-compatible object storage using ETags as opaque CAS versions. */
final class S3ObjectStore implements ObjectStore, AutoCloseable {
  private static final String SHA256_METADATA = "walgerrit-sha256";
  private static final int NOT_MODIFIED = 304;
  private static final int NOT_FOUND = 404;

  private final S3Client client;
  private final String bucket;

  S3ObjectStore(String bucket, String region, java.net.URI endpoint, boolean pathStyleAccess) {
    this(
        bucket,
        region,
        endpoint,
        pathStyleAccess,
        WalGitConfiguration.DEFAULT_S3_MAX_CONNECTIONS,
        WalGitConfiguration.DEFAULT_S3_CONNECT_TIMEOUT,
        WalGitConfiguration.DEFAULT_S3_SOCKET_TIMEOUT,
        WalGitConfiguration.DEFAULT_S3_MAX_ATTEMPTS);
  }

  /**
   * One pooled HTTP client per store. Every write is several requests and reads fan out, so
   * connections are reused rather than opened per request; the connect and socket timeouts bound
   * stalls without capping how long a healthy multi-gigabyte transfer may take, and the SDK's
   * standard strategy retries throttling, 5xx and connection failures up to {@code maxAttempts}.
   * A retried write that had already landed answers 412 to its own precondition; callers verify
   * such outcomes against the store instead of trusting the status alone.
   */
  S3ObjectStore(
      String bucket,
      String region,
      java.net.URI endpoint,
      boolean pathStyleAccess,
      int maxConnections,
      java.time.Duration connectTimeout,
      java.time.Duration socketTimeout,
      int maxAttempts) {
    var builder =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClient(
                Apache5HttpClient.builder()
                    .maxConnections(maxConnections)
                    .connectionTimeout(connectTimeout)
                    .socketTimeout(socketTimeout)
                    .connectionAcquisitionTimeout(socketTimeout)
                    .tcpKeepAlive(true)
                    .build())
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .retryStrategy(retry -> retry.maxAttempts(maxAttempts))
                    .build())
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(pathStyleAccess).build());
    if (endpoint != null) {
      builder.endpointOverride(endpoint);
    }
    client = builder.build();
    this.bucket = bucket;
  }

  @Override
  public Optional<StoredObject> get(String key) throws IOException {
    try {
      ResponseBytes<GetObjectResponse> response =
          client.getObject(
              GetObjectRequest.builder().bucket(bucket).key(key).build(),
              ResponseTransformer.toBytes());
      return Optional.of(
          new StoredObject(response.asByteArray(), version(response.response().eTag(), key)));
    } catch (S3Exception exception) {
      if (exception.statusCode() == NOT_FOUND) {
        return Optional.empty();
      }
      throw io("get", key, exception);
    } catch (RuntimeException exception) {
      throw io("get", key, exception);
    }
  }

  /**
   * Conditional GET: {@code If-None-Match} with the known ETag. An unchanged object costs one round
   * trip with no body ({@code 304 Not Modified}), which is what keeps manifest revalidation cheap.
   */
  @Override
  public ConditionalRead getIfChanged(String key, String knownVersion) throws IOException {
    if (knownVersion == null || knownVersion.isBlank()) {
      return get(key).map(ConditionalRead::changed).orElseGet(ConditionalRead::absent);
    }
    try {
      ResponseBytes<GetObjectResponse> response =
          client.getObject(
              GetObjectRequest.builder()
                  .bucket(bucket)
                  .key(key)
                  .ifNoneMatch(entityTag(knownVersion))
                  .build(),
              ResponseTransformer.toBytes());
      return ConditionalRead.changed(
          new StoredObject(response.asByteArray(), version(response.response().eTag(), key)));
    } catch (S3Exception exception) {
      if (exception.statusCode() == NOT_MODIFIED) {
        return ConditionalRead.unchanged();
      }
      if (exception.statusCode() == NOT_FOUND) {
        return ConditionalRead.absent();
      }
      throw io("conditional get", key, exception);
    } catch (RuntimeException exception) {
      throw io("conditional get", key, exception);
    }
  }

  @Override
  public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
    try {
      PutObjectResponse response =
          client.putObject(
              PutObjectRequest.builder()
                  .bucket(bucket)
                  .key(key)
                  .ifNoneMatch("*")
                  .contentLength((long) bytes.length)
                  .build(),
              RequestBody.fromBytes(bytes));
      return new StoredObject(bytes, version(response.eTag(), key));
    } catch (S3Exception exception) {
      if (isPreconditionFailure(exception)) {
        throw new ObjectAlreadyExistsException(key);
      }
      return verifyAmbiguousCreate(key, bytes, exception);
    } catch (RuntimeException exception) {
      return verifyAmbiguousCreate(key, bytes, exception);
    }
  }

  @Override
  public StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
      throws IOException {
    try {
      PutObjectResponse response =
          client.putObject(
              PutObjectRequest.builder()
                  .bucket(bucket)
                  .key(key)
                  .ifMatch(entityTag(expectedVersion))
                  .contentLength((long) bytes.length)
                  .build(),
              RequestBody.fromBytes(bytes));
      return new StoredObject(bytes, version(response.eTag(), key));
    } catch (S3Exception exception) {
      if (isPreconditionFailure(exception) || exception.statusCode() == NOT_FOUND) {
        throw new ObjectStoreConflictException(key);
      }
      throw io("conditional put", key, exception);
    } catch (RuntimeException exception) {
      throw io("conditional put", key, exception);
    }
  }

  @Override
  public void uploadIfAbsent(String key, Path source) throws IOException {
    long size = Files.size(source);
    String checksum = digest(source);
    try {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .ifNoneMatch("*")
              .contentLength(size)
              .metadata(Map.of(SHA256_METADATA, checksum))
              .build(),
          RequestBody.fromFile(source));
    } catch (S3Exception exception) {
      if (isPreconditionFailure(exception)) {
        verifyExistingImmutable(key, size, checksum);
        return;
      }
      if (matchesExistingImmutable(key, size, checksum)) {
        return;
      }
      throw io("immutable put", key, exception);
    } catch (RuntimeException exception) {
      if (matchesExistingImmutable(key, size, checksum)) {
        return;
      }
      throw io("immutable put", key, exception);
    }
  }

  @Override
  public void download(String key, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
    try {
      client.getObject(
          GetObjectRequest.builder().bucket(bucket).key(key).build(),
          ResponseTransformer.toFile(temporary));
      forceFile(temporary);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.FileAlreadyExistsException exception) {
        // Another reader materialized the same immutable object first.
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IOException(
            "Cache filesystem does not support atomic materialization: " + target, exception);
      }
    } catch (S3Exception exception) {
      throw io("download", key, exception);
    } catch (RuntimeException exception) {
      throw io("download", key, exception);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @Override
  public void delete(String key) throws IOException {
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (S3Exception exception) {
      if (exception.statusCode() == NOT_FOUND) {
        return;
      }
      throw io("delete", key, exception);
    } catch (RuntimeException exception) {
      throw io("delete", key, exception);
    }
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    return listWithVersions(prefix).stream().map(ObjectSummary::key).toList();
  }

  /** ListObjectsV2 returns each object's ETag, so a listing is also a version check. */
  @Override
  public List<ObjectSummary> listWithVersions(String prefix) throws IOException {
    List<ObjectSummary> summaries = new ArrayList<>();
    String continuationToken = null;
    do {
      try {
        ListObjectsV2Request request =
            ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .continuationToken(continuationToken)
                .build();
        ListObjectsV2Response response = client.listObjectsV2(request);
        for (S3Object object : response.contents()) {
          summaries.add(
              new ObjectSummary(
                  object.key(),
                  version(object.eTag(), object.key()),
                  object.lastModified() == null ? 0 : object.lastModified().toEpochMilli()));
        }
        continuationToken =
            Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
      } catch (S3Exception exception) {
        throw io("list", prefix, exception);
      } catch (RuntimeException exception) {
        throw io("list", prefix, exception);
      }
    } while (continuationToken != null);
    summaries.sort(Comparator.comparing(ObjectSummary::key));
    return List.copyOf(summaries);
  }

  @Override
  public void close() {
    client.close();
  }

  private StoredObject verifyAmbiguousCreate(String key, byte[] expected, Exception failure)
      throws IOException {
    try {
      Optional<StoredObject> current = get(key);
      if (current.isPresent() && Arrays.equals(current.get().bytes(), expected)) {
        return current.get();
      }
    } catch (IOException verificationFailure) {
      failure.addSuppressed(verificationFailure);
    }
    throw io("create", key, failure);
  }

  private void verifyExistingImmutable(String key, long size, String checksum) throws IOException {
    if (!matchesExistingImmutable(key, size, checksum)) {
      throw new IOException("Immutable object collision: " + key);
    }
  }

  private boolean matchesExistingImmutable(String key, long size, String checksum) {
    try {
      HeadObjectResponse head =
          client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return head.contentLength() == size
          && checksum.equals(head.metadata().get(SHA256_METADATA));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static boolean isPreconditionFailure(S3Exception exception) {
    return exception.statusCode() == 409 || exception.statusCode() == 412;
  }

  /** Versions are stored unquoted; opaque equality is all WalGerrit relies on. */
  private static String version(String eTag, String key) throws IOException {
    if (eTag == null || eTag.isBlank()) {
      throw new IOException("S3 returned no ETag for " + key);
    }
    return eTag.replace("\"", "");
  }

  /** Conditional headers carry the ETag in its RFC 7232 quoted form, as S3 itself emits it. */
  private static String entityTag(String version) {
    return version.startsWith("\"") ? version : "\"" + version + "\"";
  }

  private static String digest(Path path) throws IOException {
    MessageDigest digest = sha256();
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM has no SHA-256 provider", exception);
    }
  }

  private static void forceFile(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private static IOException io(String operation, String key, Exception cause) {
    return new IOException("S3 " + operation + " failed for " + key, cause);
  }
}
