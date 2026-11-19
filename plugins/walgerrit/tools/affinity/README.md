# Preferred routing for Gerrit HTTP writes

This small HTTP reverse proxy ranks the ready pod addresses for a project with rendezvous hashing. Git push discovery and the receive-pack POST use the same project key. Project-qualified REST writes use that key as well. Fetches, searches, numeric-only change URLs and requests whose project lives in the body go round-robin. The proxy preserves the original URL, host, credentials and request body.

Run it behind the existing TLS ingress, at the root of the Gerrit HTTP URL. Point `-service` at a headless Service with `clusterIP: None` and `publishNotReadyAddresses: false`. Its selector and readiness probe should identify the normal serving Gerrit pods. A ClusterIP DNS record would make the Kubernetes Service choose the final pod again. Endpoint selection uses the project name and the ready pod IP and port. A pod with a new IP changes placement for some projects. The published Git data stays in the shared store.

```sh
cd plugins/walgerrit/tools/affinity
go test ./...
go build -o affinity .
./affinity -listen :8080 -service gerrit-pods.gerrit.svc.cluster.local -port 8080
```

Deploy multiple instances of the router behind the ingress. Their only shared input is DNS. The default refresh period is five seconds. A negative DNS answer clears membership immediately. During transient resolver failures the last successful answer can be used for up to three periods. A stale or empty membership yields HTTP 503. When a pod disappears from the ready set, its projects route to the next-ranked pod. Between a pod failing and DNS reflecting the failure, requests to that pod can receive 502. The proxy never resubmits a dispatched write to another pod after an ambiguous upstream failure.

Use `-scheme https` for TLS between the proxy and the backends. Each pod must present a certificate valid for the configured Service name. The default HTTP mode assumes an internal trusted connection. The proxy replaces inbound forwarding headers with the immediate client's protocol, host and address. Configure Gerrit's public canonical URL and the ingress protocol handling accordingly.

SSH continues to use the existing SSH Service. Every pod remains writable, and correctness does not depend on preferred routing.
