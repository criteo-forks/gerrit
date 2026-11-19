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
package com.google.gerrit.server;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** TLS cluster membership. The in-memory certificate wraps the cluster's Ed25519 key for JSSE. */
public final class WritePeerTls {
  private WritePeerTls() {}

  public static SSLContext fromSecret(String secret) {
    byte[] seed = Base64.getDecoder().decode(secret);
    try {
      if (seed.length != Ed25519PrivateKeyParameters.KEY_SIZE) {
        throw new IllegalArgumentException("forwardingSecret must encode 32 random bytes");
      }
      var key = new Ed25519PrivateKeyParameters(seed, 0);
      PrivateKey privateKey =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(
                  new PKCS8EncodedKeySpec(
                      PrivateKeyInfoFactory.createPrivateKeyInfo(key).getEncoded()));
      var name = new X500Name("CN=walgerrit-peer");
      var cert =
          new X509v3CertificateBuilder(
                  name,
                  BigInteger.ONE,
                  Date.from(Instant.EPOCH),
                  Date.from(Instant.parse("9999-12-31T23:59:59Z")),
                  name,
                  SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(key.generatePublicKey()))
              .build(new JcaContentSignerBuilder("Ed25519").build(privateKey));
      Certificate identity = new JcaX509CertificateConverter().getCertificate(cert);
      KeyStore keys = KeyStore.getInstance("PKCS12");
      keys.load(null, null);
      keys.setKeyEntry("peer", privateKey, new char[0], new Certificate[] {identity});
      KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      km.init(keys, new char[0]);
      KeyStore trust = KeyStore.getInstance("PKCS12");
      trust.load(null, null);
      trust.setCertificateEntry("cluster", identity);
      TrustManagerFactory tm = TrustManagerFactory.getInstance("PKIX");
      tm.init(trust);
      SSLContext tls = SSLContext.getInstance("TLSv1.3");
      tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
      return tls;
    } catch (GeneralSecurityException | IOException | OperatorCreationException e) {
      throw new IllegalStateException("Cannot initialize write forwarding TLS", e);
    } finally {
      Arrays.fill(seed, (byte) 0);
    }
  }
}
