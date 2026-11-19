load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "testutil",
    testonly = True,
    srcs = glob(["**/*.java"]),
    visibility = ["//visibility:public"],
    runtime_deps = [
        "//lib/bouncycastle:bcpkix",
        "//lib/bouncycastle:bcprov",
        "//lib/bouncycastle:bcutil",
    ],
    deps = [
        "//java/com/google/gerrit/common:annotations",
        "//java/com/google/gerrit/server",
        "//lib:guava",
        "//lib:jgit",
        "//lib:servlet-api",
        "//lib/errorprone:annotations",
        "//lib/httpcomponents:httpclient",
    ],
)
